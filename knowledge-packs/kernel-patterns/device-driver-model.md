# Device Driver Model Patterns

## When to use
- Writing kernel drivers for hardware devices
- Designing a bus/device/driver abstraction for an OS or RTOS
- Understanding how hardware discovery, binding, and lifecycle work
- Implementing power management in device drivers

## Pattern

### Bus/Device/Driver Abstraction
- **Bus**: represents a communication channel (PCI, USB, I2C, platform/DT)
- **Device**: represents a hardware entity discovered on a bus
- **Driver**: code that knows how to operate a specific device (or class of devices)
- Matching: bus matches devices to drivers by ID table (vendor/device, compatible string, etc.)
- Separation: device describes "what hardware exists," driver describes "how to use it"

### Probe and Remove
- `probe()`: called when bus matches device to driver — initialize hardware, allocate resources, register interfaces
- `remove()`: called on unbind or module unload — reverse everything probe did, in reverse order
- Probe must be idempotent with respect to partial failure — clean up everything on error paths
- Use managed resources (`devm_*`) to auto-free on driver detach — eliminates most cleanup bugs
- Probe deferral (`-EPROBE_DEFER`): if a dependency (regulator, clock, GPIO) isn't ready yet, try again later

### Resource Management
- `devm_kmalloc`, `devm_ioremap`, `devm_request_irq` — lifetime tied to device, auto-freed on detach
- Request I/O memory regions before mapping — prevents conflicting drivers
- Interrupt handlers: request with appropriate flags (shared, edge/level, threaded)
- DMA: allocate coherent memory or map streaming buffers with correct direction flags
- Clock and regulator frameworks: `clk_prepare_enable()` in probe, `clk_disable_unprepare()` in remove

### Device Tree / ACPI Binding
- Device tree: declarative hardware description, driver matches on `compatible` string
- ACPI: firmware-provided device enumeration on x86 platforms
- Properties extracted from DT/ACPI at probe time — don't hardcode hardware parameters
- Bindings documented as schemas (dt-schema YAML) — validated at compile time
- Multiple compatible strings for backward compatibility: "vendor,new-chip", "vendor,old-chip"

### Power Management
- Runtime PM: device suspended when not in use, resumed on demand
- `pm_runtime_get_sync()` before hardware access, `pm_runtime_put()` after
- System suspend/resume callbacks: save device state, restore on resume
- Autosuspend delay: keep device active for N ms after last use to avoid thrashing
- Wake-capable devices: configure as wakeup source for system suspend

### Userspace Interface
- Character device (`/dev/xxx`): file operations (open, read, write, ioctl, poll)
- Sysfs attributes: simple key-value pairs for configuration and status
- Netlink: structured messages for event notification and configuration
- Subsystem frameworks (input, network, block, V4L2): provide standard interfaces, driver fills in ops
- Avoid custom ioctls when a subsystem framework fits — userspace tools already exist

## Gotchas / Anti-patterns
- Forgetting cleanup on probe error paths — resource leaks accumulate with repeated bind/unbind
- Non-`devm_` resource allocation without matching free in remove — guaranteed leak
- Accessing hardware before clocks/regulators are enabled — undefined hardware behavior
- Blocking in probe for hardware that takes seconds to initialize — blocks the bus
- Hardcoding register addresses instead of reading from device tree/ACPI
- Race between probe and userspace access — device must be fully initialized before registering interfaces
- Using platform_device for enumerable buses (PCI, USB) — defeats hot-plug and auto-discovery

## References
- Linux Device Model: https://docs.kernel.org/driver-api/driver-model/
- Linux Device Drivers (3rd ed, Corbet/Rubini/Kroah-Hartman): https://lwn.net/Kernel/LDD3/
- Device Tree Specification: https://www.devicetree.org/specifications/
- Linux Kernel Module Programming Guide: https://sysprog21.github.io/lkmpg/
- Runtime Power Management: https://docs.kernel.org/power/runtime_pm.html
