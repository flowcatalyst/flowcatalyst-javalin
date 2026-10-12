-- 28 of the seeded 1.0 schemas did not describe the data their operations
-- emit (the event's data() through Json.MAPPER): the dispatch-pool events send
-- poolId, not dispatchPoolId; the event-type schema events send specVersion,
-- not version; the user, application, service-account, connection and
-- subscription events send fewer fields than the schemas required (email,
-- name, scope, applicationType, endpoint, eventTypes, code, ...); some optional
-- fields are never sent; and platform:iam:client:applications-updated may send
-- null for an empty list (Go does; Java sends [], which the new schema admits).
-- The seed now holds the corrected schemas (EmittedEventSchemaTest checks every
-- code), but the seeder never replaces an attached 1.0, so this does it for
-- existing databases: only while the 1.0 is still exactly what a seed wrote
-- (each code had one seeded form in every earlier Java and Go seed). A schema
-- someone changed is theirs and is left alone. Idempotent. Go migration 072,
-- the same SQL; either may run first. Flyway has no Down; Go's 072 Down
-- restores the seeded forms.
UPDATE msg_event_type_spec_versions sv
   SET schema_content = s.new_schema::jsonb, updated_at = NOW()
  FROM msg_event_types et,
       (VALUES
        ('platform:admin:connection:created',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"clientId":{"type":["string","null"]},"code":{"type":"string"},"connectionId":{"type":"string"},"endpoint":{"type":"string"},"name":{"type":"string"},"serviceAccountId":{"type":"string"}},"required":["connectionId","code","name","endpoint","serviceAccountId"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"connectionId":{"type":"string"},"name":{"type":"string"}},"required":["connectionId","code","name"],"type":"object"}'),
        ('platform:admin:connection:deleted',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"clientId":{"type":["string","null"]},"code":{"type":"string"},"connectionId":{"type":"string"}},"required":["connectionId","code"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"connectionId":{"type":"string"}},"required":["connectionId","code"],"type":"object"}'),
        ('platform:admin:connection:updated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"connectionId":{"type":"string"},"endpoint":{"type":["string","null"]},"name":{"type":["string","null"]},"status":{"type":["string","null"]}},"required":["connectionId","code"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"connectionId":{"type":"string"},"name":{"type":"string"}},"required":["connectionId","name"],"type":"object"}'),
        ('platform:admin:dispatch-pool:archived',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"dispatchPoolId":{"type":"string"}},"required":["dispatchPoolId","code"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"poolId":{"type":"string"}},"required":["poolId","code"],"type":"object"}'),
        ('platform:admin:dispatch-pool:created',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"clientId":{"type":["string","null"]},"code":{"type":"string"},"dispatchPoolId":{"type":"string"},"name":{"type":"string"}},"required":["dispatchPoolId","code","name"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"name":{"type":"string"},"poolId":{"type":"string"}},"required":["poolId","code","name"],"type":"object"}'),
        ('platform:admin:dispatch-pool:deleted',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"dispatchPoolId":{"type":"string"}},"required":["dispatchPoolId","code"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"poolId":{"type":"string"}},"required":["poolId","code"],"type":"object"}'),
        ('platform:admin:dispatch-pool:updated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"concurrency":{"minimum":0,"type":["integer","null"]},"dispatchPoolId":{"type":"string"},"name":{"type":["string","null"]},"rateLimit":{"minimum":0,"type":["integer","null"]}},"required":["dispatchPoolId"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"name":{"type":"string"},"poolId":{"type":"string"}},"required":["poolId","name"],"type":"object"}'),
        ('platform:admin:eventtype:schema-added',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"eventTypeId":{"type":"string"},"mimeType":{"type":"string"},"schemaType":{"type":"string"},"version":{"type":"string"}},"required":["eventTypeId","version","mimeType","schemaType"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"eventTypeId":{"type":"string"},"specVersion":{"type":"string"}},"required":["eventTypeId","specVersion"],"type":"object"}'),
        ('platform:admin:eventtype:schema-deprecated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"eventTypeId":{"type":"string"},"version":{"type":"string"}},"required":["eventTypeId","version"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"eventTypeId":{"type":"string"},"specVersion":{"type":"string"}},"required":["eventTypeId","specVersion"],"type":"object"}'),
        ('platform:admin:eventtype:schema-finalised',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"deprecatedVersion":{"type":["string","null"]},"eventTypeId":{"type":"string"},"version":{"type":"string"}},"required":["eventTypeId","version"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"deprecatedVersion":{"type":["string","null"]},"eventTypeId":{"type":"string"},"specVersion":{"type":"string"}},"required":["eventTypeId","specVersion"],"type":"object"}'),
        ('platform:admin:eventtype:updated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"description":{"type":["string","null"]},"eventTypeId":{"type":"string"},"name":{"type":["string","null"]}},"required":["eventTypeId"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"description":{"type":["string","null"]},"eventTypeId":{"type":"string"},"name":{"type":"string"}},"required":["eventTypeId","name"],"type":"object"}'),
        ('platform:admin:subscription:created',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"clientId":{"type":["string","null"]},"code":{"type":"string"},"connectionId":{"type":"string"},"eventTypes":{"items":{"type":"string"},"type":"array"},"name":{"type":"string"},"subscriptionId":{"type":"string"}},"required":["subscriptionId","code","name","connectionId","eventTypes"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"name":{"type":"string"},"subscriptionId":{"type":"string"}},"required":["subscriptionId","code","name"],"type":"object"}'),
        ('platform:admin:subscription:paused',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"subscriptionId":{"type":"string"}},"required":["subscriptionId","code"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"subscriptionId":{"type":"string"}},"required":["subscriptionId"],"type":"object"}'),
        ('platform:admin:subscription:resumed',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"subscriptionId":{"type":"string"}},"required":["subscriptionId","code"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"subscriptionId":{"type":"string"}},"required":["subscriptionId"],"type":"object"}'),
        ('platform:admin:subscription:updated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"eventTypesAdded":{"items":{"type":"string"},"type":"array"},"eventTypesRemoved":{"items":{"type":"string"},"type":"array"},"name":{"type":["string","null"]},"subscriptionId":{"type":"string"}},"required":["subscriptionId","eventTypesAdded","eventTypesRemoved"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"name":{"type":"string"},"subscriptionId":{"type":"string"}},"required":["subscriptionId","name"],"type":"object"}'),
        ('platform:iam:application:activated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"},"code":{"type":"string"}},"required":["applicationId","code"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"}},"required":["applicationId"],"type":"object"}'),
        ('platform:iam:application:created',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"},"applicationType":{"type":"string"},"code":{"type":"string"},"name":{"type":"string"}},"required":["applicationId","code","name","applicationType"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"},"code":{"type":"string"},"name":{"type":"string"}},"required":["applicationId","code","name"],"type":"object"}'),
        ('platform:iam:application:deactivated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"},"code":{"type":"string"}},"required":["applicationId","code"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"}},"required":["applicationId"],"type":"object"}'),
        ('platform:iam:application:deleted',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"},"code":{"type":"string"},"name":{"type":"string"}},"required":["applicationId","code","name"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"},"code":{"type":"string"}},"required":["applicationId","code"],"type":"object"}'),
        ('platform:iam:application:updated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"},"description":{"type":["string","null"]},"name":{"type":["string","null"]}},"required":["applicationId"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":"string"},"name":{"type":"string"}},"required":["applicationId","name"],"type":"object"}'),
        ('platform:iam:client:applications-updated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"clientId":{"type":"string"},"disabledRemoved":{"items":{"type":"string"},"type":"array"},"enabledAdded":{"items":{"type":"string"},"type":"array"},"enabledApplicationIds":{"items":{"type":"string"},"type":"array"}},"required":["clientId","enabledApplicationIds","enabledAdded","disabledRemoved"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"clientId":{"type":"string"},"disabledRemoved":{"items":{"type":"string"},"type":["array","null"]},"enabledAdded":{"items":{"type":"string"},"type":["array","null"]},"enabledApplicationIds":{"items":{"type":"string"},"type":["array","null"]}},"required":["clientId","enabledApplicationIds","enabledAdded","disabledRemoved"],"type":"object"}'),
        ('platform:iam:serviceaccount:created',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"applicationId":{"type":["string","null"]},"clientIds":{"items":{"type":"string"},"type":"array"},"code":{"type":"string"},"name":{"type":"string"},"serviceAccountId":{"type":"string"}},"required":["serviceAccountId","code","name","clientIds"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"code":{"type":"string"},"name":{"type":"string"},"serviceAccountId":{"type":"string"}},"required":["serviceAccountId","code","name"],"type":"object"}'),
        ('platform:iam:serviceaccount:updated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"clientIdsAdded":{"items":{"type":"string"},"type":"array"},"clientIdsRemoved":{"items":{"type":"string"},"type":"array"},"description":{"type":["string","null"]},"name":{"type":["string","null"]},"serviceAccountId":{"type":"string"}},"required":["serviceAccountId","clientIdsAdded","clientIdsRemoved"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"name":{"type":"string"},"serviceAccountId":{"type":"string"}},"required":["serviceAccountId","name"],"type":"object"}'),
        ('platform:iam:user:created',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"clientId":{"type":["string","null"]},"email":{"type":"string"},"emailDomain":{"type":"string"},"isAnchorUser":{"type":"boolean"},"name":{"type":"string"},"principalId":{"type":"string"},"scope":{"type":"string"}},"required":["principalId","email","emailDomain","name","scope","isAnchorUser"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"email":{"type":"string"},"principalId":{"type":"string"}},"required":["principalId","email"],"type":"object"}'),
        ('platform:iam:user:deactivated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"principalId":{"type":"string"},"reason":{"type":["string","null"]}},"required":["principalId"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"principalId":{"type":"string"}},"required":["principalId"],"type":"object"}'),
        ('platform:iam:user:deleted',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"principalId":{"type":"string"}},"required":["principalId"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"email":{"type":"string"},"principalId":{"type":"string"}},"required":["principalId","email"],"type":"object"}'),
        ('platform:iam:user:password-reset-completed',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"email":{"type":"string"},"principalId":{"type":"string"}},"required":["principalId","email"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"principalId":{"type":"string"}},"required":["principalId"],"type":"object"}'),
        ('platform:iam:user:updated',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"email":{"type":["string","null"]},"name":{"type":["string","null"]},"principalId":{"type":"string"}},"required":["principalId"],"type":"object"}',
         '{"$schema":"http://json-schema.org/draft-07/schema#","additionalProperties":false,"properties":{"name":{"type":"string"},"principalId":{"type":"string"}},"required":["principalId","name"],"type":"object"}')
       ) AS s(code, old_schema, new_schema)
 WHERE et.id = sv.event_type_id
   AND et.code = s.code
   AND sv.version = '1.0'
   AND sv.schema_content = s.old_schema::jsonb;
