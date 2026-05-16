### Fixes
- Stop double-decoding the incoming call URI. `getSchemeSpecificPart` already returns the decoded form; the extra `Uri.decode` could corrupt numbers containing a literal `%`.
- Fix a window in `nextId` where a failed SharedPreferences commit could let the next rule reuse the same id. The id counter is now only persisted as part of the rule write itself.
- Undo-after-delete restores the rule at its original list position instead of appending at the bottom.

### Internal
- Single shared regex find helper across the editor and the data layer.
- Import dialog buttons share their action logic; import no longer decodes the JSON twice.
- `RuleRepository` serialization routes through `RuleIO` so the JSON config lives in one place.
- `decide()` collapses the per-action filter into a local helper.
- Unit tests share one `testRule()` factory.
- Dropped misleading `@Transient` on a body property and one dead defensive effect in the import flow.
