# Bootloader Design Patterns

## When to use
- Devices requiring field firmware updates (OTA or wired)
- Safety-critical systems needing guaranteed boot into a working image
- Products with secure boot chain requirements
- Any embedded device deployed where physical access is difficult or impossible

## Pattern

### Boot Sequence
- Bootloader lives in protected flash region — never overwritten during firmware updates
- Stage 1: hardware init (clock, RAM, watchdog), validate firmware image, jump to application
- Validation: check CRC/hash of firmware image before executing — don't boot corrupted code
- Timeout to application: if no update command received within N seconds, boot normally
- Store boot metadata (active slot, boot count, last update status) in dedicated flash/EEPROM region

### Secure Boot
- Chain of trust: ROM bootloader verifies Stage 1, Stage 1 verifies application
- Cryptographic signature verification (ECDSA, Ed25519) — not just CRC
- Public key stored in OTP (one-time programmable) fuses or protected flash
- Reject unsigned or tampered images before any code executes
- Debug port lockdown: disable JTAG/SWD in production to prevent bypass

### A/B Partitioning
- Two firmware slots (A and B) in flash — one active, one for receiving updates
- Update writes to inactive slot while active slot continues running
- After successful write and verification, mark new slot as "pending boot"
- Bootloader tries pending slot; if it boots and self-confirms, mark as "good"
- If new slot fails (no confirmation within timeout), revert to previous good slot

### Firmware Update Protocol
- Chunk-based transfer: image split into blocks, each block individually CRC-checked
- Resume support: track which blocks received so interrupted updates can continue
- Version validation: reject downgrades unless explicitly authorized
- Atomic commit: the active slot marker only changes after full image verification
- Communication agnostic: update protocol should work over UART, USB, BLE, or OTA

### Recovery Mode
- Dedicated recovery image in protected flash — minimal code, always boots
- Trigger via: hardware button held during boot, boot failure counter exceeded, magic byte in RAM
- Recovery can accept firmware updates but cannot run the full application
- Recovery image is updated rarely and through a more controlled process
- Boot failure counter: increment before boot, reset by application after successful start

### Flash Management
- Wear leveling for metadata that changes frequently (boot count, config)
- Erase-before-write: flash pages must be erased before writing — plan page boundaries
- Write in order within a page — some flash technologies don't support random write after erase
- Flash write protection on bootloader and recovery regions — hardware-enforced immutability
- Power-loss safety: never leave flash in a half-written state — use write-then-commit pattern

## Gotchas / Anti-patterns
- Single firmware slot with in-place update — power loss during write bricks the device
- CRC without cryptographic signature — attacker can craft valid CRC for malicious firmware
- Bootloader that can overwrite itself — one bad update permanently bricks the device
- No rollback mechanism — bad firmware shipped to the field with no recovery path
- Boot loop without failure counter — device rapidly reboots forever, draining battery
- Firmware version stored only inside the image — bootloader can't check version before loading
- Testing updates only in development — field conditions (slow links, power interrupts) differ

## References
- MCUboot (Zephyr/Mbed): https://docs.mcuboot.com/
- ARM TrustedFirmware-M: https://www.trustedfirmware.org/projects/tf-m/
- SWUpdate (Linux embedded): https://swupdate.org/
- IETF SUIT Manifest: https://datatracker.ietf.org/doc/html/rfc9019
- Barr Group Bootloader Design: https://barrgroup.com/embedded-systems/how-to/firmware-update-designs
