package romram

import common.Bus
import common.Clocked
import cpucore.*
import instruction.addInstruction
import instruction.fillEmptyProgramData
import io.reactivex.Emitter
import io.reactivex.Observable
import io.reactivex.observables.ConnectableObservable
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import rom4001.Rom4001

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RomTests {
    val rom: Rom4001
    var dataBus = Bus()
    var ioBus = Bus()
    var clk: ConnectableObservable<Int>
    var sync: Clocked<Int>          // Sync signal between devices
    var cmRom: Clocked<Int>         // ROM select signal from CPU
    var cmRam: Clocked<Int>         // RAM select signals (4 bits) from CPU

    // Set everything up
    init {
        dataBus.init(4, "Test Bus")
        ioBus.init(4, "Test IO Bus")
        clk = Observable.create { it: Emitter<Int> ->
            emitter = it
        }.publish()
        clk.connect()
        sync = Clocked(1, clk)        // Sync signal between devices
        cmRom = Clocked(1, clk)       // ROM select signal from CPU
        cmRam = Clocked(0xf, clk)     // RAM select signals (4 bits) from CPU
        rom = Rom4001(dataBus, ioBus, clk, sync, cmRom)
    }

    @Nested
    inner class IOTests {
        @Test
        fun Sync() {
            rom.reset()
            var data = mutableListOf<UByte>()
            fillEmptyProgramData(data)
            rom.loadProgram(data)
            // It will take two cycles since sync is sent on clock 7
            runOneMemCycle(rom, 0, NOP, 0)
            runOneMemCycle(rom, 0, NOP, 0)
            assertThat(rom.syncSeen).isEqualTo(true)
        }

        @Test
        fun InstRead() {
            rom.reset()
            var data = mutableListOf<UByte>()
            // Sync the device
            runOneMemCycle(rom, 0, NOP, 0)
            runOneMemCycle(rom, 0, NOP, 0)
            // Generate a simple program
            addInstruction(data, 0x01U, 0)
            addInstruction(data, 0x23U, 0)
            addInstruction(data, 0x45U, 0)
            addInstruction(data, 0x67U, 0)
            fillEmptyProgramData(data)
            rom.loadProgram(data)
            for (i in 0..3) {
                val res = runOneMemCycle(rom, i.toLong(), null, 0)
                assertThat(res).isEqualTo(data[i].toUInt())
            }
        }

        @Test
        fun ChipID() {
            rom.reset()
            var data = mutableListOf<UByte>()
            // Sync the device
            runOneMemCycle(rom, 0, NOP, 0)
            runOneMemCycle(rom, 0, NOP, 0)
            // Generate a simple program
            addInstruction(data, 0xDEU)
            fillEmptyProgramData(data)
            rom.loadProgram(data)
            // Make sure the rom responds to chip 0 addresses (the default)
            var res = runOneMemCycle(rom, 0, null, 0)
            assertThat(res).isEqualTo(data[0].toUInt())
            assertThat(rom.chipSelected).isTrue()

            // Now change the chip ID
            rom.setRomID(3)
            // Make sure the rom does NOT respond to chip 0 addresses
            res = runOneMemCycle(rom, 0, null, 0)
            assertThat(res).isNotEqualTo(data[0].toUInt())
            assertThat(rom.chipSelected).isFalse()

            // Finally, check the ROM with the right address
            // Make sure the rom responds to chip 3 addresses
            res = runOneMemCycle(rom, 0x300, null, 0)
            assertThat(res).isEqualTo(data[0].toUInt())
            assertThat(rom.chipSelected).isTrue()
        }

        @Test
        fun SRC() {
            rom.reset()
            var data = mutableListOf<UByte>()
            // Sync the device
            runOneMemCycle(rom, 0, NOP, 0)
            runOneMemCycle(rom, 0, NOP, 0)
            // Generate a simple program
            addInstruction(data, SRC)
            addInstruction(data, FIM)
            fillEmptyProgramData(data)
            rom.loadProgram(data)
            // Make sure the rom responds to chip ID 0
            runOneSRCCycle(rom, 0, 0U)
            assertThat(rom.srcDetected).isTrue()

            // Make sure the rom does NOT respond to chip ID != 0
            runOneSRCCycle(rom, 0, 0x10U)
            assertThat(rom.srcDetected).isFalse()

            // Make sure the rom does not confuse a FIM with a SRC
            // since they share the same upper 4 bits
            runOneSRCCycle(rom, 1, 0U)
            assertThat(rom.srcDetected).isFalse()
        }
        @Test
        fun IoWrite() {
            rom.reset()
            var data = mutableListOf<UByte>()
            // Sync the device
            runOneMemCycle(rom, 0, NOP, 0)
            runOneMemCycle(rom, 0, NOP, 0)
            // Generate a simple program
            addInstruction(data, SRC)
            addInstruction(data, WRR)
            fillEmptyProgramData(data)
            rom.loadProgram(data)
            val ioData = 0xBUL

            // Run the SRC command first to arm the device
            runOneSRCCycle(rom, 0, 0U)
            assertThat(rom.srcDetected).isTrue()
            runOneIoWriteCycle(rom, 1, ioData)
            assertThat(ioBus.value).isEqualTo(ioData)
        }
        @Test
        fun IoRead() {
            rom.reset()
            var data = mutableListOf<UByte>()
            // Sync the device
            runOneMemCycle(rom, 0, NOP, 0)
            runOneMemCycle(rom, 0, NOP, 0)
            // Generate a simple program
            addInstruction(data, SRC)
            addInstruction(data, RDR)
            fillEmptyProgramData(data)
            rom.loadProgram(data)
            val ioData = 0xBUL
            // Write the ioData to the ioBus
            ioBus.write(ioData)

            // Run the SRC command first to arm the device
            runOneSRCCycle(rom, 0, 0U)
            assertThat(rom.srcDetected).isTrue()
            val res = romram.runOneIOReadCycle(rom, 1, 0U, 0)
            assertThat(res.second).isEqualTo(ioData)
        }

    }
}