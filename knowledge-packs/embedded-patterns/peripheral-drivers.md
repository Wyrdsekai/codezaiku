# Peripheral Driver Patterns

## When to use
- Interfacing MCU with sensors, actuators, displays, or communication modules
- Building a hardware abstraction layer for portability across MCU families
- Designing interrupt-driven or DMA-based I/O for performance
- Communicating over I2C, SPI, UART, or other bus protocols

## Pattern

### HAL Design
- Thin abstraction over hardware registers — enough to swap MCU, not a full OS driver model
- Interface per peripheral type: `uart_init()`, `uart_write()`, `uart_read()` with opaque handle
- Platform-specific implementation behind the interface — one file per MCU family
- Configuration struct passed at init time — baud rate, pin mapping, DMA channel
- HAL owns pin muxing and clock enable for its peripheral — caller shouldn't touch registers

### DMA (Direct Memory Access)
- Use DMA for bulk transfers — UART RX/TX, SPI data, ADC sequences
- Circular DMA buffer for continuous streams (audio, sensor data) — no CPU intervention
- Double-buffering: DMA fills one buffer while application processes the other
- DMA complete interrupt signals the application — don't poll the DMA status register
- Cache coherence: on Cortex-M7+, invalidate/clean cache before/after DMA transfers

### Interrupt Handling
- ISR captures event, defers processing to task (RTOS queue or flag)
- Clear the interrupt flag in the ISR — failure to clear causes infinite re-entry
- Shared interrupt lines: ISR must check status register to identify the actual source
- Nested interrupts: higher-priority ISR preempts lower — keep all ISRs short regardless
- Disable specific interrupt during configuration changes, not global interrupt disable

### Bus Protocols

#### I2C
- Address-based bus, multi-device, 2 wires (SDA, SCL), typically 100kHz-400kHz
- Pull-up resistors required — value depends on bus capacitance and speed
- Clock stretching: slave holds SCL low when not ready — master must support it
- Bus recovery: if SDA stuck low, toggle SCL 9 times then issue STOP condition
- Multi-master: implement arbitration or designate single master to avoid complexity

#### SPI
- Full-duplex, 4 wires (MOSI, MISO, SCK, CS), MHz speeds
- One chip-select per device — directly driven GPIO, active low
- Clock polarity (CPOL) and phase (CPHA) must match device datasheet exactly
- DMA natural fit — fixed-size transfers with known byte count
- No flow control — slave must keep up or data is lost

#### UART
- Point-to-point, 2 wires (TX, RX), asynchronous — both sides must agree on baud rate
- Circular DMA RX buffer with idle-line detection for variable-length messages
- Framing: start byte, length, payload, CRC — UART has no built-in message boundaries
- Flow control (RTS/CTS) for high-throughput or when receiver can't keep up
- Baud rate mismatch causes garbled data — verify with scope if communication fails

### Error Handling
- Bus errors (NACK, timeout, framing error) must be detected and reported
- Retry with backoff for transient bus errors — don't retry forever
- Peripheral reset as last resort — re-initialize the peripheral after unrecoverable error
- Watchdog on communication timeout — peripheral hardware can lock up

## Gotchas / Anti-patterns
- Blocking I/O in a real-time task — use DMA or interrupt-driven I/O
- Not checking return values from HAL calls — silent failure leads to mysterious bugs
- Global interrupt disable for "thread safety" — blocks all other interrupts, breaks timing
- Reading a volatile hardware register into a local variable and reading it again (value changes)
- Assuming I2C pull-ups are on the module — many breakout boards omit them
- SPI CS toggling between bytes in a multi-byte transfer — some devices reset on CS rise
- UART without framing — no way to detect message boundaries after a byte is lost

## References
- ARM CMSIS Drivers: https://arm-software.github.io/CMSIS_6/latest/Driver/index.html
- STM32 HAL Reference: https://www.st.com/en/embedded-software/stm32cube-mcu-mpu-packages.html
- Zephyr Device Driver Model: https://docs.zephyrproject.org/latest/kernel/drivers/index.html
- I2C Specification: https://www.nxp.com/docs/en/user-guide/UM10204.pdf
- SPI Tutorial (Analog Devices): https://www.analog.com/en/resources/analog-dialogue/articles/introduction-to-spi-interface.html
