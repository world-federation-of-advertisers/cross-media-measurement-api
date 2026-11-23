# Updating hierarchy metadata

Shared ancestor metadata, such as a campaign name copied to its descendants,
cannot be renamed through `UpdateEventGroup` or `BatchUpdateEventGroups`.
This restriction applies regardless of the number of descendants.

An ordinary EventGroup can update metadata owned by its own level. Changing an
`id_entity_metadata_key` moves it within the hierarchy; supplied metadata must
match any existing destination ancestors.
