# Predictive analytics over email — use real signal, not a stub

The spec wants "who emails next / when" from time-series patterns per sender (or category). Build it from
the actual timestamps; don't `return None` or return the first key.

- Group the send timestamps per sender (and/or per category). A sender with no history contributes nothing.
- Score by **recency + frequency**: more messages and a more-recent last message → higher likelihood. A
  simple, real score: `count / (1 + days_since_last)`. The predicted "next sender" is the argmax.
- For "when": use the mean (or median) inter-arrival gap for that sender — `last_seen + mean_gap` is a
  defensible next-contact estimate. One data point → no interval (say so), don't fabricate one.
- Read the field names the rest of the pipeline actually uses (`from_addr` vs `sender`, `date` vs `ts`) —
  a name mismatch makes `fit()` see "unknown" for every record and the model degenerates silently.

Confidence is the normalized score, not a constant. Test it: give sender A three recent messages and sender
B one old one, assert A is predicted over B; then add two recent messages for B and assert the prediction
flips. A predictor that ignores its input can't satisfy that flip.
