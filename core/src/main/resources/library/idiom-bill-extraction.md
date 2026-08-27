# Bill / payment extraction — pull real values out of the text, attribute them correctly

Each extracted record needs FOUR things from the actual email, not placeholders:
- **amount**: find a currency-tagged number — `\$\s*[\d,]+(\.\d{2})?`, plus `€`/`£` if the spec lists them.
  Strip the thousands separators before parsing (`1,234.50` → `1234.50`). Reject negatives/zero if the
  spec means charges.
- **currency**: from the symbol/code you matched (`$`→USD, `€`→EUR, `£`→GBP), not a hardcoded default.
- **date**: parse the email's Date header (or a date in the body); don't silently fall back to `now()` for
  every record — that destroys the monthly grouping.
- **category**: keyword-map the sender/subject/body (utilities, subscriptions, …). One miss → "other".
- **sender**: the FROM address — NOT the subject. (Common bug: `sender = subject`.) Keep them distinct.

Only emit a record when the email actually looks like a bill (an amount was found AND an invoice/receipt/
payment cue is present); otherwise return nothing for that email.

Totals: group by `category` and by month-of-`date`. The all-time total per category MUST equal the sum of
that category's monthly totals — assert that conservation. Test with one known bill: assert the exact
amount, currency, and category, and that adding a second bill in the same month moves the monthly total up
by exactly that amount.
