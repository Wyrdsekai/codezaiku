# Pagination

## When to use
- Any API endpoint that returns a list of items that could grow unbounded
- Choosing a pagination strategy based on dataset characteristics
- Balancing usability (total count, random access) against performance

## Pattern

### Offset-based pagination
- Parameters: `?offset=20&limit=10` or `?page=3&page_size=10`
- Returns: items for the requested window, total count (optional)
- Allows random access to any page
- Best for: small, stable datasets; admin panels where "jump to page N" is needed

**Tradeoffs**:
- Inconsistent results when data changes between pages (items skip or duplicate)
- `OFFSET N` in SQL scans and discards N rows — performance degrades with large offsets
- Total count requires a separate `COUNT(*)` query which can be expensive

### Cursor-based pagination
- Parameters: `?cursor=abc123&limit=10`
- The cursor is an opaque token encoding the position (typically the last item's sort key)
- Returns: items after the cursor, a `next_cursor` (null if no more pages)
- Best for: large datasets, real-time feeds, infinite scroll

**Implementation**:
- Encode the sort column value as the cursor: `WHERE created_at > :cursor ORDER BY created_at LIMIT :limit`
- Use the indexed column as the cursor key for O(log n) seeks
- Cursors should be opaque to clients — base64-encode them so clients do not parse or construct them

### Keyset pagination
- A specific form of cursor-based pagination using the actual column values as the cursor
- `WHERE (created_at, id) > (:last_created_at, :last_id) ORDER BY created_at, id LIMIT 10`
- Compound keys handle ties (multiple items with the same timestamp)
- Stable results even when data is inserted or deleted between pages

### Response envelope
```json
{
  "data": [ ... ],
  "pagination": {
    "next_cursor": "eyJjcmVhdGVkX2F0IjoiMjAyNi0wMS0xNSJ9",
    "has_more": true
  }
}
```
- For offset-based: include `total_count`, `page`, `page_size`
- For cursor-based: include `next_cursor` (or `next` link) and `has_more`
- Always include a `has_more` boolean — clients should not guess based on array length

### Total count considerations
- Exact total count is expensive on large tables (`COUNT(*)` without index support)
- Options: omit total count, return an estimate, cache the count, or provide it only for filtered queries below a threshold
- Many UIs work fine with "showing 1-20 of many" instead of "showing 1-20 of 1,847,293"

### Default and maximum limits
- Always enforce a default page size (e.g., 20) and a maximum (e.g., 100)
- Reject or cap requests exceeding the maximum — never return unbounded result sets
- Document the default and maximum in the API reference

## Gotchas / Anti-patterns
- **No pagination at all**: returning 10,000 items in a single response; always paginate list endpoints
- **Large offsets in production**: `?offset=100000` causes full table scans; switch to cursor-based for deep pages
- **Mutable cursors**: using a row number or offset as a "cursor"; rows shift when data changes. Use a stable key
- **Returning different page sizes than requested**: if the client asks for 10 and you return 7, they cannot distinguish "partial last page" from "some items filtered out"
- **No default limit**: a missing `limit` param returns the entire dataset; always default to a reasonable page size

## References
- Slack API: cursor-based pagination documentation
- Stripe API: auto-pagination and cursor design
- Use The Index, Luke: pagination chapter (SQL performance implications)
- Relay specification: connection-based pagination (GraphQL)
