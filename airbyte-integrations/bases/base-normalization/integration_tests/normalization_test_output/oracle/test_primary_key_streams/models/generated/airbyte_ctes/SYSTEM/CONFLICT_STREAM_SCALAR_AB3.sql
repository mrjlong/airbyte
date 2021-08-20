{{ config(schema="SYSTEM", tags=["top-level-intermediate"]) }}
-- SQL model to build a hash column based on the values of this record
select
    ora_hash(
        'ID' || '~' ||
        'CONFLICT_STREAM_SCALAR'
    ) as AIRBYTE_CONFLICT_STREAM_SCALAR_HASHID,
    tmp.*
from {{ ref('CONFLICT_STREAM_SCALAR_AB2') }} tmp
-- CONFLICT_STREAM_SCALAR

