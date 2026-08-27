# Power Management Patterns

## When to use
- Battery-powered devices where runtime is a key product requirement
- Energy-harvesting systems (solar, vibration) with unpredictable power budgets
- Always-on IoT devices deployed in inaccessible locations
- Any embedded system where thermal constraints limit sustained power draw

## Pattern

### Sleep Modes
- **Sleep/Idle**: CPU halted, peripherals active, wake on any interrupt, microsecond wake time
- **Deep sleep**: CPU + most peripherals off, RAM retained, wake on RTC/GPIO, millisecond wake
- **Shutdown**: everything off, RAM lost, wake on external pin only, full boot required
- Choose the deepest sleep mode compatible with your wake latency requirement
- Transition to sleep immediately when no work pending — don't spin-wait

### Wake Sources
- RTC alarm for periodic tasks (sensor sampling, heartbeats, time sync)
- GPIO edge detection for external events (button press, accelerometer interrupt, radio IRQ)
- UART/I2C activity detection for communication-triggered wake
- Watchdog timeout as a safety wake — ensures device doesn't sleep through a fault
- Configure only the wake sources you need — each enabled source adds leakage current

### Duty Cycling
- Active only during scheduled windows; sleep the rest of the time
- Duty cycle = (active time / total cycle time) — target below 1% for multi-year battery life
- Align activities: batch sensor reads, process, transmit in one wake window
- Stagger wake times in sensor networks to avoid synchronized transmission collisions
- Adaptive duty cycling: increase frequency when interesting events detected, decrease during quiet periods

### Power Budgets
- Measure current draw in each state (active, sleep, deep sleep) with a current probe
- Calculate energy per operation: (current * time) for each wake cycle
- Battery capacity (mAh) / average current draw (mA) = estimated runtime (hours)
- Account for self-discharge, temperature effects, and voltage cutoff (battery isn't fully usable)
- Budget headroom: design for 80% of theoretical battery capacity

### Peripheral Power Control
- Gate power to unused peripherals — I2C sensors drawing milliamps while idle
- Disable internal pull-ups on unused pins — each costs microamps
- Configure unused GPIO as output low or analog input (check MCU datasheet for lowest leakage)
- External power switches (load switches, MOSFETs) for high-draw peripherals
- Ramp-up time: budget time for peripherals to stabilize after power-on before reading

### Radio Power (for wireless devices)
- Radio transmit dominates power budget — minimize transmit time and frequency
- Batch data and send in bursts rather than frequent small packets
- Lower transmit power when link budget allows — power scales with distance squared
- Use radio sleep/standby between transmissions
- Receive window scheduling: listen only when expecting a response

## Gotchas / Anti-patterns
- Leaving debug UART enabled in production — constant peripheral clock drain
- Polling a sensor in a tight loop instead of using interrupt-driven wake
- Forgetting to disable the ADC after measurement — it draws significant current
- LED indicators left on in deployed devices — a single LED can exceed MCU deep-sleep current
- Not measuring actual current — datasheet values are typical, your board has parasitics
- Voltage regulator quiescent current exceeding the MCU sleep current
- Brown-out detector set too aggressively — resets device during transmit power spikes

## References
- STM32 Low-Power Modes: https://www.st.com/resource/en/application_note/an4621.pdf
- Nordic Semiconductor Power Profiler: https://www.nordicsemi.com/Products/Development-tools/Power-Profiler-Kit-2
- ESP32 Sleep Modes: https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/system/sleep_modes.html
- TI Power Management Reference: https://www.ti.com/power-management/overview.html
- Battery University: https://batteryuniversity.com/
