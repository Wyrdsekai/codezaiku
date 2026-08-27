# Real-Time Audio Patterns

## When to use
- Building audio applications requiring sub-10ms latency (instruments, effects, DAWs)
- Implementing audio callbacks that must never miss a deadline
- Designing thread-safe communication between audio and UI threads
- Achieving glitch-free audio on consumer hardware

## Pattern

### Audio Callback Design
- Audio hardware calls your callback at regular intervals requesting N samples
- Callback must complete within the buffer period: `buffer_size / sample_rate` seconds
- At 256 samples / 48kHz, you have ~5.3ms to fill the buffer — every callback, without exception
- The callback is the highest-priority real-time code in the application
- Callback receives input buffer (microphone/line-in) and must fill output buffer

### Forbidden Operations in Audio Thread
- No memory allocation or deallocation (malloc/free/new/delete) — non-deterministic timing
- No locking (mutex, semaphore) — unbounded blocking from priority inversion
- No system calls that may block (file I/O, network, logging to file)
- No exceptions (in C++) — stack unwinding is expensive and non-deterministic
- No standard library containers that allocate (vector::push_back, map::insert)
- Rule of thumb: if the operation has unbounded worst-case time, it cannot be in the audio thread

### Lock-Free Communication
- **SPSC ring buffer** (Single Producer, Single Consumer): main thread writes, audio thread reads
- Use atomic operations for head/tail pointers — no mutex needed
- Pre-allocate fixed-capacity buffers at startup — no runtime allocation
- Parameter changes: write new value to atomic variable, audio thread reads each callback
- Event notification from audio to main: write to ring buffer, main thread polls on timer

### Latency Management
- Total latency = input buffer + processing + output buffer
- Lower buffer size = lower latency but higher CPU overhead and risk of underrun
- Typical usable range: 64-512 samples (1.3-10.7ms at 48kHz)
- ASIO (Windows), CoreAudio (macOS), JACK/PipeWire (Linux) provide low-latency paths
- Monitor CPU usage per callback — if approaching 70% of budget, increase buffer or optimize

### Thread Architecture
- **Audio thread**: real-time, runs callback, highest priority, never blocks
- **Processing thread**: non-real-time, loads files, computes FFTs for display, manages plugins
- **UI thread**: renders interface, handles user input, sends parameter changes to audio
- Communication: lock-free queues between all thread pairs
- File streaming: processing thread reads from disk into ring buffer, audio thread reads from ring buffer

### Plugin Hosting
- Load plugins in processing thread — initialization may allocate, do I/O
- Plugin process() called from audio callback — same real-time constraints apply
- Parameter changes from UI queued and applied at block boundaries — sample-accurate automation
- Plugin state save/load on processing thread — may involve serialization and file I/O
- Sandbox suspect plugins in separate process — crash doesn't take down the host

### Denormalized Float Prevention
- Near-zero feedback loops produce denormalized floats (exponent underflow)
- Denormalized arithmetic is 10-100x slower on most CPUs
- Fix: set FPU flush-to-zero (FTZ) and denormals-are-zero (DAZ) flags at audio thread entry
- Alternative: inject tiny DC offset or noise to keep signals above denormal range
- Check: profile with long reverb tails or idle feedback effects — spikes indicate denormals

## Gotchas / Anti-patterns
- Printing debug output in the audio callback — console I/O blocks
- Using std::mutex to share data with the audio thread — priority inversion causes glitches
- Allocating temporary buffers per callback — allocator is not real-time safe
- Assuming audio callback timing is perfectly periodic — jitter is normal, design for it
- Ignoring buffer underruns — they indicate a latent reliability problem that worsens under load
- Processing thread blocking audio thread for plugin scan — audio must never wait for non-RT thread
- Float comparison for parameter changes — use epsilon or hysteresis to avoid jitter

## References
- Ross Bencina — "Real-Time Audio Programming 101": http://www.rossbencina.com/code/real-time-audio-programming-101-time-waits-for-nothing
- JACK Audio API: https://jackaudio.org/
- PortAudio (cross-platform audio I/O): http://www.portaudio.com/
- "Audio Anecdotes" (Ken Greenebaum) — practical audio programming patterns
- PipeWire Documentation: https://docs.pipewire.org/
