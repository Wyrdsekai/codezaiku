# Email threading — group messages into conversations (PRECEDENCE is the whole trick)

A message's own `Message-ID` is its IDENTITY, not a grouping key. If you key threads on `Message-ID`
first, every message lands in its own thread and nothing groups. Group by the PARENT pointers instead:

- A reply carries `In-Reply-To` and/or `References` headers that hold the **Message-ID of the message it
  replies to**. Those are the link to the parent — use them FIRST.
- Use a message's own `Message-ID` only as the thread ROOT key (a message with no parent starts a thread).
- Fall back to a normalized `Subject` (strip a leading `Re:` / `Fwd:`) when there are no references at all.

    WRONG  key = messageId || inReplyTo || references || subject   # → one thread per message, no grouping
    RIGHT  key = references || inReplyTo || messageId || normalizedSubject

The defining invariant: a reply with `In-Reply-To: <X>` MUST end up in the same thread as the message whose
`Message-ID: <X>`. (`References` may list several ids oldest→newest; the conversation root is typically the
first, the immediate parent the last — either resolves to the same thread when you union by shared id.)

Per-thread rollups the spec usually wants: message count, participants (the set of unique senders),
first/last timestamp.

Test it for real (not "threads is not empty"): build a ROOT message and a REPLY whose `In-Reply-To` is the
root's `Message-ID`, thread them, and assert **thread count == 1** and that thread's **message count == 2**.
Add an unrelated third message and assert thread count goes to **2**. That distinguishes real grouping from
the per-message-id bug, which would report 2 then 3.
