# Audio Effects Patterns

## When to use
- Implementing signal processing effects for music production or game audio
- Building effect chains (reverb, delay, compression, EQ, distortion)
- Understanding the algorithms behind common studio effects
- Designing real-time audio processing with low latency

## Pattern

### Reverb
- **Algorithmic reverb**: network of delay lines with feedback and allpass filters (Schroeder, Dattorro)
- **Convolution reverb**: convolve input with recorded impulse response of a real space
- Early reflections: discrete taps from a delay line modeling first bounces off walls
- Late reverb: dense, diffuse tail — allpass diffusors create density from sparse delay taps
- Parameters: decay time (RT60), pre-delay, damping (high-frequency rolloff in tail), room size, wet/dry mix
- FDN (Feedback Delay Network): matrix-connected delay lines, standard modern algorithmic approach

### Delay
- Store incoming samples in circular buffer, read back at a fixed offset (delay time)
- Feedback: feed output back into input — creates repeating echoes
- Modulated delay: slowly vary the read position with an LFO — produces chorus and flanger effects
- Tap delay: multiple read positions from one buffer for rhythmic echo patterns
- Ping-pong: alternate echoes between left and right channels
- Interpolation: when delay time is fractional samples, interpolate (linear minimum, allpass or cubic preferred)

### Compression / Dynamics
- **Compressor**: reduces dynamic range — attenuates signal above threshold
- Envelope follower: tracks signal level (RMS or peak) with attack/release time constants
- Gain computation: `gain_reduction = (level - threshold) * (1 - 1/ratio)` above threshold
- Knee: soft knee applies gradual ratio transition around threshold — sounds more natural
- **Limiter**: compressor with infinite ratio and fast attack — prevents clipping
- **Gate**: silences signal below threshold — removes noise during quiet passages
- Sidechain: use a different signal (kick drum) to trigger compression — ducking effect

### Equalization
- **Parametric EQ**: adjustable frequency, gain, and Q (bandwidth) per band
- **Shelving EQ**: boost/cut all frequencies above (high shelf) or below (low shelf) a frequency
- **Graphic EQ**: fixed frequency bands with adjustable gain — less precise but intuitive
- Implementation: cascaded biquad filters, one per band
- Linear-phase EQ (via FIR filter): no phase distortion, but adds latency — used in mastering
- Frequency analyzer: FFT-based display showing spectrum — visual feedback for EQ decisions

### Distortion
- **Waveshaping**: apply nonlinear function to signal — `tanh(x)`, polynomial, lookup table
- **Clipping**: hard clip at threshold (digital), soft clip with gradual saturation (tube-like)
- **Bitcrushing**: reduce bit depth — quantization noise creates lo-fi character
- **Sample rate reduction**: decimate samples — aliased, retro digital sound
- Oversampling essential: nonlinear processing creates harmonics above Nyquist — upsample, distort, downsample
- Tone control after distortion: shape the harsh harmonics — most guitar amps filter post-clipping

### Modulation Effects
- **Chorus**: short modulated delay (20-50ms) mixed with dry signal — thickening
- **Flanger**: very short modulated delay (0-10ms) with feedback — jet/comb filter sweep
- **Phaser**: chain of allpass filters with modulated center frequency — notch sweep without comb structure
- **Tremolo**: amplitude modulation by LFO — volume fluctuation
- **Ring modulation**: multiply signal by carrier sine — metallic, inharmonic tones
- All modulation effects: LFO rate (speed) and depth (intensity) as primary controls

## Gotchas / Anti-patterns
- Delay feedback >= 1.0 — signal grows without bound, infinite volume
- Compression with zero attack time — removes all transients, sounds flat and lifeless
- Distortion without oversampling — aliasing folds back as inharmonic garbage
- EQ bands fighting each other — boosting then cutting the same frequency range
- Reverb on every track independently — muddy mix (use send/return bus instead)
- Hard clipping as intentional distortion without post-filter — harsh, unmusical
- Not compensating for latency when effects introduce delay (lookahead compressor, linear-phase EQ)

## References
- "DAFX: Digital Audio Effects" (Udo Zolzer) — comprehensive effects algorithms
- Audio EQ Cookbook (Robert Bristow-Johnson): https://www.w3.org/2011/audio/audio-eq-cookbook.html
- Valhalla DSP (reverb design): https://valhalladsp.com/blog/
- "Designing Audio Effect Plugins in C++" (Will Pirkle)
- Freeverb (Schroeder reverb reference): https://ccrma.stanford.edu/~jos/pasp/Freeverb.html
