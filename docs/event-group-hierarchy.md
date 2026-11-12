# Updating ancestor metadata

To rename a campaign shared by many leaf EventGroups:

1. Read the campaign EventGroup and its EventGroupHierarchy. Copy the metadata
   keys listed in the campaign level's `entity_metadata_keys` into a new Struct.
2. Change the campaign name in that Struct, preserving other values at that
   level. Omitted keys will be removed. Do not include entity ID keys.
3. Call `SetEventGroupAncestorMetadata` with the campaign's resource name,
   the replacement Struct, and a new UUID4 `request_id`. Save the operation name.
4. Poll `google.longrunning.Operations.GetOperation` until `done` is true. Success
   returns the updated campaign in `response`; failure is reported in `error`.
   Retry a lost response with the same request and ID to recover the operation.

For a level whose only metadata key is `campaign_name`, the request is:

```textproto
name: "dataProviders/123/eventGroups/456"
entity_metadata {
  fields {
    key: "campaign_name"
    value { string_value: "Summer campaign" }
  }
}
```

This changes the display name, not the resource name or entity ID. The subtree
need not fit in one transaction or a 50-item `BatchUpdateEventGroups` request.
The same endpoint serves `google.longrunning.Operations`; operation names are
`dataProviders/{data_provider}/operations/{operation}`. Operations and request
IDs are retained for at least 24 hours after completion. Cancellation through
`CancelOperation` is best effort; publication may win the race and succeed.

## Server workflow

1. Persist the operation and acquire a fenced subtree lock. Writes affecting its
   metadata or membership (including creates, deletes, moves and overlapping
   metadata operations) return `ABORTED`; hierarchy configuration changes wait.
2. Stage a new metadata version and its search values in bounded transactions,
   checkpointing each batch. Workers resume after transient failures or restarts.
   Reads, including metadata searches, use one committed version and continue
   seeing the old values during staging.
3. In one small transaction, publish the version, record operation success and
   release the lock. Terminal failure or cancellation before publication records
   an operation error and releases the lock, leaving committed values unchanged;
   staged data can be discarded asynchronously.

The worker, versioned metadata/search storage and Operations service are required
Kingdom implementation work; the API definition alone does not provide them.
