# Event group synchronization

`EventGroups.SyncEventGroups` lets an authorized operator maintain a local
inventory of the latest event group state across all data providers and
measurement consumers. The destination consumes versioned API records and
canonical resource names. Operators can choose their own database schema or
storage format.

The caller must have deployment-wide operator read authorization. Ordinary
data provider or measurement consumer credentials do not grant this access.
Authorization is checked on every request; possession of a token does not grant
access.

## Scope

Set `parent` to `dataProviders/-`. The method includes all entity types and
resources in the `DELETED` state. Each returned resource uses its canonical
name and the `BASIC` view. Activity summaries and independently managed event
group activity resources are outside this sync.

An initial cycle returns the complete inventory. A later cycle returns the
latest state of each resource changed since the preceding checkpoint. Multiple
updates to a resource between checkpoints are coalesced into its latest state.

## Snapshots and checkpoints

The server chooses one snapshot time for each cycle, at or after the previous
checkpoint. Every page reads resource
versions from that same snapshot, even if live resources are updated or deleted
while the caller is retrieving pages. The response exposes this time as
`read_time`; the opaque page token preserves it between requests.

For a previous checkpoint W and a new snapshot T, the incremental cycle reads
the snapshot at T and returns the latest state of resources changed after W
through T. The server uses internal modification timestamps to select changed
resources and a stable unique resource key to distinguish equal timestamps when
paging. A page token continues strictly after the last returned position. These
internal timestamps are not exposed on the public `EventGroup`. The checkpoint
is derived from the database snapshot and its committed state, rather than the
client's clock or the largest update time returned on a page.

For example, a cycle reading at 10:05 returns a resource as it existed at 10:05,
even if a later page is requested after a deletion at 10:06. Once that cycle has
completed, the next cycle includes the deletion after 10:05. Deleted resources
retain their identity, deleted state, and deletion update time for the supported
checkpoint lifetime. If those records can no longer be supplied, the server
rejects the checkpoint instead of silently returning an incomplete inventory.

## Client loop

1. Start with an empty `sync_token` for a new inventory, or the last completed
   sync token for an incremental cycle. Leave `page_token` empty.
2. Durably apply the returned resources, keyed by canonical `name`. An ACTIVE
   resource updates the destination; a DELETED resource removes or marks the
   corresponding destination record.
3. Save `next_page_token` only after the page has been applied. Continue with
   that page token and the same `parent` and `sync_token`. `page_size` may change.
4. When `next_page_token` is empty, save `next_sync_token` only after all pages
   have been durably applied. Clear the in-progress page token.
5. Poll again using the completed sync token and an empty page token.

The server may return a short or empty page with a continuation token. Only an
empty `next_page_token` indicates completion. The final response always supplies
`next_sync_token`, including when the cycle returned no changed resources.

## Destination writes and retries

Persist completed-cycle and in-progress-page checkpoints separately. Where the
destination supports transactions, commit a page's record writes and its next
checkpoint together. If writes require multiple batches, advance the checkpoint
only after every batch succeeds. Run one writer per destination checkpoint, or
coordinate writers so that concurrent runs cannot advance it inconsistently.

Apply records idempotently. A repeated page from the same snapshot contains the
same resource states. A crash after some writes and before checkpointing is
recovered by retrying the page.

To guard against late responses from an older cycle, retain the response's
`read_time` for each destination key and reject writes from older snapshots.
Records from a newer snapshot can replace older destination state; equal
snapshot times may be replayed. Retain this marker for deleted keys, even if the
destination removes their resource rows. The snapshot time is sufficient for
this comparison because every returned resource reflects its state at that
snapshot. The client does not need the resource's internal update timestamp.

Destination configuration and field mappings belong to the operator's sync
process. This API supplies records and tokens; storage destination settings are
outside the request contract.

## Expired tokens

A fixed snapshot is retained for a bounded period. A valid page token whose
snapshot is no longer available results in `FAILED_PRECONDITION` with
`google.rpc.ErrorInfo.reason` equal to `SYNC_PAGE_TOKEN_EXPIRED`. Restart from
the last completed sync token with an empty page token. The new cycle chooses a
fresh snapshot and may replay already applied records.

A completed sync token can outlive the previous snapshot because the next cycle
reads current resource versions at a new snapshot. If the checkpoint itself is
no longer supported, the server returns `FAILED_PRECONDITION` with reason
`SYNC_TOKEN_EXPIRED`. Begin a new inventory and reconcile the destination against
the complete result, including removal of keys absent from that inventory.
Finish the full inventory before reconciling missing keys.

Malformed tokens or tokens used with different request arguments result in
`INVALID_ARGUMENT`. A caller without operator authorization receives
`PERMISSION_DENIED`.
