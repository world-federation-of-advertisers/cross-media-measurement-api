# Updating ancestor metadata

To rename a campaign shared by many leaf EventGroups:

1. Read the campaign EventGroup and its EventGroupHierarchy. Copy the metadata
   keys listed in the campaign level's `entity_metadata_keys` into a new Struct.
2. Change the campaign name in that Struct, preserving other values at that
   level. Omitted keys will be removed. Do not include entity ID keys.
3. Call `SetEventGroupAncestorMetadata` with the campaign's resource name and
   the replacement Struct. The server updates the campaign and all non-deleted
   descendants atomically, returning the updated campaign.

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

This changes the display name, not the resource name or entity ID. Callers do not
enumerate leaves, so the 50-item `BatchUpdateEventGroups` limit does not apply.
If the subtree exceeds transaction capacity, the call fails with
`RESOURCE_EXHAUSTED` and leaves every resource unchanged. Splitting the rename
into leaf batches is not a supported fallback.
