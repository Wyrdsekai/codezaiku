# Audio Plugin Architecture Patterns

## When to use
- Building audio effects or instruments as plugins for DAWs
- Choosing between plugin formats (VST3, AU, CLAP)
- Designing parameter automation, preset management, and state persistence
- Creating a plugin that works reliably across different hosts

## Pattern

### Plugin Formats
- **VST3** (Steinberg): most widely supported, COM-based C++ API, complex but complete
- **Audio Units** (Apple): macOS/iOS only, Cocoa-based, required for Logic Pro and GarageBand
- **CLAP** (free/open): modern design, simpler API, lock-free parameter model, growing adoption
- **LV2** (Linux): open standard, URI-based extension system, primary format for Linux DAWs
- Cross-platform strategy: use a framework (JUCE, iPlug2, NIH-plug) to target multiple formats from one codebase

### Plugin Lifecycle
- **Discovery**: host scans plugin directories, loads metadata (name, I/O config, categories)
- **Instantiation**: host creates plugin instance — allocate resources, set initial state
- **Activation**: host provides sample rate and buffer size — prepare DSP state
- **Processing**: host calls process() each audio block — real-time constraints apply
- **Deactivation**: host stops audio — free DSP resources, keep UI alive
- **Destruction**: host unloads plugin — release all resources

### Parameter Design
- Declare parameters at instantiation: ID, name, range, default, display string
- Internal value normalized (0-1) and mapped to domain (20Hz-20kHz logarithmic, -inf to +6dB)
- Thread-safe parameter access: host may change parameters from any thread
- CLAP model: host writes parameters, plugin reads — no locking needed
- VST3 model: parameter changes queued as events, applied sample-accurately during process()
- Smoothing: filter parameter changes to avoid zipper noise — one-pole lowpass or linear ramp

### Automation
- Sample-accurate automation: parameter changes timestamped within a buffer
- Split processing at automation points for sample-accurate response
- Or: interpolate between parameter values across the buffer — simpler, usually sufficient
- Report parameter changes from plugin to host (e.g., UI knob turned) — host records to automation lane
- Automation range should match parameter range — host displays normalized, plugin denormalizes

### State Save/Load (Presets)
- Serialize all parameter values and internal state to binary blob or structured format
- Host calls getState() and setState() — must be deterministic (same state in = same sound out)
- Version the state format: new plugin version must load old presets (add fields, don't remove)
- Factory presets: bundled with plugin, demonstrate capabilities, serve as starting points
- User presets: saved/loaded by host or plugin's own preset browser

### GUI Patterns
- Plugin provides its own editor window — host embeds it in the plugin chain view
- Separate rendering from audio processing — GUI runs on main thread, audio on RT thread
- Decoupled updates: audio thread writes meter/analysis data to lock-free buffer, GUI reads periodically
- Resizable GUI: host may resize the window — design for flexible layout or define min/max size
- GPU-accelerated rendering for complex visualizations (spectrum analyzer, oscilloscope)
- Headless mode: plugin must function without GUI — some hosts run in batch/offline mode

### Testing
- Offline rendering test: process known input, verify output matches expected output (bit-exact or tolerance)
- Parameter fuzzing: randomize all parameters while processing — detect crashes and denormals
- State round-trip: save state, load state, verify output matches — detect serialization bugs
- Multi-instance: run many instances simultaneously — detect global state or resource conflicts
- Validate with pluginval (VST3/AU/CLAP host-compliance testing tool)

## Gotchas / Anti-patterns
- Global mutable state — breaks when multiple plugin instances run in the same host
- GUI code calling into audio DSP directly — thread safety violation
- Not handling sample rate changes — host may change rate without destroying the instance
- Blocking in process() to wait for GUI or file I/O — audio glitch
- Parameter IDs that change between versions — host automation data becomes meaningless
- Assuming fixed buffer size — some hosts vary buffer size between callbacks
- Plugin that crashes on scan — host blacklists it, user never sees it

## References
- VST3 SDK Documentation: https://steinbergmedia.github.io/vst3_dev_portal/
- CLAP Specification: https://github.com/free-audio/clap
- Audio Units Programming Guide: https://developer.apple.com/documentation/audiotoolbox/audio_unit_v3_plug-ins
- JUCE Framework: https://juce.com/
- NIH-plug (Rust plugin framework): https://github.com/robbert-vdh/nih-plug
- pluginval (validation tool): https://github.com/Tracktion/pluginval
