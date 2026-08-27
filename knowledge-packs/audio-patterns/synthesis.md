# Audio Synthesis Patterns

## When to use
- Building software synthesizers or sound generators
- Creating procedural audio for games, music production, or sound design
- Implementing modular synthesis architectures
- Generating test signals, tones, or noise for audio system testing

## Pattern

### Oscillators
- **Sine**: pure tone, fundamental building block — `sin(2 * pi * freq * t)`
- **Saw/square/triangle**: harmonically rich waveforms for subtractive synthesis
- Naive waveforms alias at high frequencies — use band-limited methods (PolyBLEP, wavetable, BLIT)
- Phase accumulator: increment phase each sample by `freq / sample_rate`, wrap at 1.0
- Phase accumulator avoids drift and handles frequency modulation cleanly

### Envelopes (ADSR)
- **Attack**: time to rise from 0 to peak level
- **Decay**: time to fall from peak to sustain level
- **Sustain**: level held while note is held (not a time — a level)
- **Release**: time to fall from sustain to 0 after note-off
- Exponential curves sound more natural than linear — human perception is logarithmic
- Smoothing: avoid discontinuities (clicks) at segment transitions — short crossfade or one-pole filter

### Modulation
- **LFO** (Low Frequency Oscillator): modulates parameters (pitch, filter, amplitude) below audio rate
- **Vibrato**: LFO on pitch, typically 5-7 Hz with a few cents depth
- **Tremolo**: LFO on amplitude — distinct from vibrato despite common confusion
- **Envelope modulation**: use ADSR or custom shapes to modulate filter cutoff, amplitude, etc.
- Modulation matrix: route any modulation source to any parameter with adjustable depth

### Wavetable Synthesis
- Store one or more cycles of a waveform in a lookup table
- Read from table using phase accumulator — interpolate between samples (linear or cubic)
- Multiple tables at different frequencies for band-limiting — crossfade between tables based on pitch
- Wavetable morphing: blend between different waveforms for evolving timbres
- Memory efficient: one cycle stored, arbitrary length output generated

### FM Synthesis
- One oscillator (modulator) modulates the frequency of another (carrier)
- Produces complex spectra from simple sine waves — sidebands at `carrier +/- n * modulator`
- Modulation index controls spectral richness: `index = modulator_amplitude / modulator_frequency`
- Algorithms: different operator routing topologies (6-operator DX7 has 32 algorithms)
- Feedback FM: operator modulates itself — produces saw-like spectra

### Subtractive Synthesis
- Start with harmonically rich source (saw, pulse, noise)
- Shape spectrum with filters: low-pass for warmth, band-pass for resonance, high-pass for brightness
- Filter envelope: modulate cutoff over time for dynamic timbre
- Resonance (Q): boost at cutoff frequency, self-oscillates at maximum — becomes a sine oscillator
- Multiple oscillators detuned slightly for chorus/unison thickening

### Additive Synthesis
- Sum of individual sine waves (partials) at controlled frequencies, amplitudes, and phases
- Complete spectral control — any sound can theoretically be synthesized
- Expensive: hundreds of oscillators needed for complex timbres
- Inverse FFT: generate spectrum in frequency domain, convert to time domain — efficient for many partials
- Resynthesis: analyze recorded sound into partials, modify, resynthesize

## Gotchas / Anti-patterns
- Naive square/saw wave generation — audible aliasing artifacts at high frequencies
- Hard-switching between oscillator waveforms — causes clicks (zero-crossing or crossfade needed)
- Linear ADSR envelopes — sound robotic and unmusical for most applications
- FM synthesis without understanding ratio relationships — produces inharmonic, metallic sounds
- Wavetable without interpolation — quantization noise and pitch-dependent timbre shifts
- Not normalizing oscillator output — clipping when multiple oscillators sum together
- Forgetting to handle note-off during attack/decay — envelope must transition to release from any stage

## References
- "The Computer Music Tutorial" (Curtis Roads) — comprehensive synthesis reference
- Julius O. Smith — Physical Audio Signal Processing: https://ccrma.stanford.edu/~jos/pasp/
- Valhalla DSP Blog (Sean Costello): https://valhalladsp.com/blog/ — practical DSP insights
- "Designing Sound" (Andy Farnell) — procedural audio synthesis
- Surge Synthesizer (open source): https://surge-synthesizer.github.io/ — reference implementation
