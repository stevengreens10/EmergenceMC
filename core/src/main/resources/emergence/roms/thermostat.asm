; Thermostat program: holds a plate near 45 °C, reading its thermistor through the ADC
.asm em8
;
; For the EM-8 processor. Output port: o0 the ADC's chip select (high: idle), o1 its clock, o2 the heater's MOSFET,
; o3 the heater's LED, o4-o7 the reading's top four bits on LEDs. Input port: i0 the ADC's data out.
; The ADC presents its reading's most significant bit when selected, and the next bit on each falling edge of its
; clock: eight bits are read here, the top eight of its ten.

SET = 138          ; the reading at about 45 °C (10 kΩ NTC over 5.1 kΩ: 0.54 of the supply)
HEATER = 0x0C      ; the heater and its LED (o2, o3)

reading = 0        ; memory: the reading being shifted in
count = 1          ; bits still to read
heat = 2           ; the heater's bits, 0 or HEATER
show = 3           ; the LEDs' bits (the reading's top four)

        LDI 0
        ST heat
        ST show
loop:   LD heat         ; select the ADC (it takes its sample), its clock low
        OR show
        OUT
        LDI 0
        ST reading
        LDI 8
        ST count
bit:    LD reading      ; reading = reading * 2 + the bit on its data out
        SHL
        ST reading
        IN
        ANDI 1
        OR reading
        ST reading
        LD heat         ; its clock high, then low: it presents the next bit
        OR show
        ORI 2
        OUT
        LD heat
        OR show
        OUT
        DEC count
        JNZ bit
        LD heat         ; deselect it
        OR show
        ORI 1
        OUT
        LD reading      ; the reading's top four bits on the LEDs
        ANDI 0xF0
        ST show
        LD reading      ; colder than the setpoint: heat; else not
        CMPI SET
        JC cold
        LDI 0
        ST heat
        JMP loop
cold:   LDI HEATER
        ST heat
        JMP loop
