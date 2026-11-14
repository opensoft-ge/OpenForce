# OpenForce compatibility ledger

Baseline: empty directory, initialized as an independent repository. Guide: `../skills/open-force/SKILL.md` and its bundled inventory. This iteration establishes an API foundation, not complete product parity.

Target: **Salesforce REST v68.0 (Winter 27)**, primary documentation accessed 2026-11-13: [reference](https://resources.docs.salesforce.com/latest/latest/en-us/sfdc/pdf/api_rest.pdf). Tests exercise our implementation; no licensed vendor conformance capture was available.

| ID | Capability | Implemented behavior | Boundary / next work |
|---|---|---|---|
| OF-001 | CRM records | Account, Contact, Lead, Opportunity CRUD | Bounded fields and fixed local picklists |
| OF-002 | Record identity | 15-character lookup and validated 18-character checksum | Local generated IDs; OwnerId is configured local identity |
| OF-003 | Relationships | Contact/Opportunity AccountId lookup; block deletion with children | No cascades, sharing rules or full relationship queries |
| OF-004 | SOQL | Projection, one string equality, single-field ORDER BY and LIMIT | No joins, aggregates, queryMore or complete grammar |
| OF-005 | Metadata | sObjects index and field listing | Describe is intentionally partial |
| OF-006 | Remaining product | Missing | OAuth, Apex, flows, bulk/composite APIs, UI, reports, enterprise sharing |

Cross-cutting foundation: persistent SQLite transactions, local bearer identity, tenant isolation, role checks, strict input validation, Docker deployment and exact coverage gates. Missing across the product: vendor OAuth/SSO, fine-grained ACLs, production migrations/backup tooling, distributed workers and vendor-identical UI. Coverage measures implemented Java, not the missing inventory.

Acceptance: valid lifecycle persists through app restart; invalid inputs fail without partial writes; tenant boundaries and roles hold; exact executable-line coverage includes every production Java class; branch counts are reported separately. Docker smoke exercises a real HTTP server. See [validation](validation.md).

## OF-004 continuation — ordered queries, 13 November 2026

Starting HEAD `aef657b`, branch main, clean tree. Inventory A4/B4; selected ordered reads over composite writes (OF-007, missing) and full sharing (OF-008, missing) to make existing query clients useful before larger transaction and identity work. Target remains REST v68.0. [Official SOQL ORDER BY reference](https://developer.salesforce.com/docs/platform/salesforce-soql-sosl/guide/sforce-api-calls-soql-select-orderby.html), retrieved 2026-11-13, specifies direction, null placement, locale-sensitive text and restrictions. This implementation supports a documented single-field English-style subset; see `api.md`. No vendor runtime conformance is asserted.

Implemented in `src/main/java/ge/opensoft/openforce/ApiController.java`. Acceptance in `src/test/java/ge/opensoft/openforce/ContractTest.java` covers numeric and text ordering, omitted values, direction/null defaults and overrides, WHERE/LIMIT composition, unselected sort fields, restart persistence, private-record and tenant filtering, empty selections, and explicit rejection of unsupported grammar/field types. Two positive regression cases failed against the old parser. Current Java measurements and reproduction: [validation](validation.md).

## Java 25 migration

The runtime, HTTP acceptance harness and build now use Java 25 / Spring Boot 4.1.2. Existing persistence and implemented API behavior are retained. Current coverage and test results supersede earlier stack-specific measurements: see [validation](validation.md). This migration does not expand vendor-compatibility claims.
