# OpenForce API

All routes use `/services/data/v68.0`.
`GET /sobjects` and `GET /sobjects/{type}/describe` return bounded metadata.
`POST /sobjects/{type}` creates and returns HTTP 201 `{id, success, errors}`.
`GET|PATCH|DELETE /sobjects/{type}/{id}` reads or returns HTTP 204 on mutation.
`GET /query?q=SELECT%20Id,Name%20FROM%20Account` supports one optional `WHERE field = 'value'` then an optional `ORDER BY field [ASC|DESC] [NULLS FIRST|LAST]`, and `LIMIT 0..2000`; escaped quote/backslash are supported. More than 2000 results without LIMIT fail explicitly.
Account requires Name; Contact requires LastName; Lead requires LastName and Company; Opportunity requires Name, StageName and ISO CloseDate. Amount must be finite and within the local bound. Null clearing is unsupported. See `OBJECTS` and `STAGES` in `ApiController.java` for the exact field and picklist registry.
Records are private to their local owner except tenant admins. Related AccountId access is checked. Invalid checksum or casing is rejected. Error envelopes are Salesforce-shaped; individual error-code equivalence is not claimed.

Requests require `Authorization: Bearer <token>`. `/healthz` is public. Unknown/repeated query parameters and unknown body fields are rejected. Authentication uses local TOKENS configuration, not vendor OAuth. Transactions commit only successful requests.

Ordering supports one stored field, including unselected fields; ASC and NULLS FIRST are defaults. Numeric Amount sorts numerically; other supported values use uppercase string ordering (English-style, no locale setting). Equal values retain local insertion order. Description (long text), Status/StageName (picklists), and AccountId (relationship semantics) are rejected as sort fields. No multi-field order, expressions, joins or locale/currency conversion. Visibility and WHERE filtering precede sorting; LIMIT follows it.
