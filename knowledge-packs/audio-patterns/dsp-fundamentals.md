# DSP Fundamentals

## When to use
- Building audio processing software (DAWs, effects, analysis tools)
- Understanding sampling, aliasing, and frequency-domain operations
- Implementing filters, FFT analysis, or spectral processing
- Managing audio buffers in real-time or offline processing contexts

## Pattern

### Sampling and Nyquist
- Sampling rate must be at least 2x the highest frequency to avoid aliasing (Nyquist theorem)
- Standard rates: 44100 Hz (CD), 48000 Hz (video/pro), 96000 Hz (high-res recording)
- Anti-aliasing filter before ADC: remove frequencies above Nyquist before sampling
- Oversampling for nonlinear processing: process at 2-4x rate to push aliasing above audibility, then downsample
- Bit depth determines dynamic range: 16-bit = ~96dB, 24-bit = ~144dB, 32-bit float = ~1500dB

### FFT (Fast Fourier Transform)
- Converts time-domain signal to frequency-domain representation
- FFT size (N) determines frequency resolution: `freq_resolution = sample_rate / N`
- Larger N = better frequency resolution but worse time resolution (uncertainty principle)
- Common sizes: 512, 1024, 2048, 4096 — must be power of 2 for standard Cooley-Tukey
- Output: N/2+1 complex bins representing magnitude and phase at each frequency

### Windowing
- Applying a window function before FFT reduces spectral leakage
- Rectangular (no window): best frequency resolution, worst leakage
- Hann: good general-purpose, moderate resolution and leakage
- Blackman-Harris: excellent leakage suppression, wider main lobe
- Overlap-add/overlap-save: process overlapping windowed segments and recombine for continuous output
- Typical overlap: 50% (Hann) or 75% (more accurate but higher CPU)

### Filtering
- **Low-pass**: passes frequencies below cutoff, attenuates above — smoothing, anti-aliasing
- **High-pass**: passes frequencies above cutoff — removing DC offset, rumble
- **Band-pass**: passes a frequency range — isolating frequency bands
- **Notch/band-reject**: removes a narrow frequency band — removing hum or feedback
- Biquad filter: second-order IIR, building block for all standard filter types
- Cascading biquads: higher-order filters built from series of biquad sections

### Buffer Management
- Audio processed in fixed-size blocks (buffer size): 64, 128, 256, 512, 1024 samples
- Smaller buffers = lower latency but higher CPU overhead (more callbacks per second)
- Circular/ring buffers for producer-consumer between threads
- Double-buffering: fill one buffer while the other is being played/processed
- Buffer underrun: not enough samples ready when hardware needs them — causes audible glitch (click/pop)

### Convolution
- Convolve signal with impulse response to apply acoustic characteristics (reverb, cab sim)
- Direct convolution: O(N*M) — impractical for long impulse responses
- Partitioned convolution: FFT-based, split IR into segments, process in frequency domain
- Uniform partitioning: all segments same size — simple but latency = segment size
- Non-uniform partitioning: small first segment (low latency), larger later segments (efficiency)

## Gotchas / Anti-patterns
- Processing at wrong sample rate — pitch shift, incorrect filter cutoff frequencies
- FFT without windowing — spectral leakage smears frequency content across bins
- IIR filter instability from accumulated floating-point error — use double precision for coefficients
- Denormalized floats in feedback loops — CPU spikes as FPU handles denorms (flush-to-zero mode fixes this)
- Allocating memory in the audio thread — allocator latency causes buffer underrun
- Not accounting for filter group delay — phase shift causes comb filtering when mixed with dry signal
- Processing stereo as independent mono — breaks stereo image for some effects

## References
- "Understanding Digital Signal Processing" (Richard Lyons) — accessible DSP textbook
- Julius O. Smith — DSP Online Books: https://ccrma.stanford.edu/~jos/
- KissFFT (lightweight FFT library): https://github.com/mborgerding/kissfft
- Audio EQ Cookbook (Robert Bristow-Johnson): https://www.w3.org/2011/audio/audio-eq-cookbook.html
- FFTW (Fastest Fourier Transform in the West): https://www.fftw.org/
