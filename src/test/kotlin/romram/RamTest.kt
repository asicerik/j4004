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
import ram4002.Ram4002
import rom4001.Rom4001

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RamTests {
    val ram: Ram4002
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
        ram = Ram4002(dataBus, ioBus, clk, sync, cmRom)
        ram.createRamMemory(16,4)
    }

    @Nested
    inner class IOTests {
        @Test
        fun Sync() {
            ram.reset()
            // It will take two cycles since sync is sent on clock 7
            runOneCycle(ram, 0)
            runOneCycle(ram, 0)
            assertThat(ram.syncSeen).isEqualTo(true)
        }

        @Test
        fun SRC() {
            ram.reset()
            // Sync the device
            runOneCycle(ram, 0)
            runOneCycle(ram, 0)

            // Make sure the ram responds to chip ID 0
            runOneSRCCycle(ram, 0, 0L, SRC.toLong())
            assertThat(ram.srcDetected).isTrue()

            // Make sure the ram does NOT respond to chip ID != 0
            runOneSRCCycle(ram, 0, 0x40L, SRC.toLong())
            assertThat(ram.srcDetected).isFalse()

            // Make sure the ram does not confuse a FIM with a SRC
            // since they share the same upper 4 bits
            runOneSRCCycle(ram, 0, 0L, FIM.toLong())
            assertThat(ram.srcDetected).isFalse()
        }
        @Test
        fun IoWrite() {
            ram.reset()
            // Sync the device
            runOneCycle(ram, 0)
            runOneCycle(ram, 0)
            val ioData:Long = 0xB

            // Run the SRC command first to arm the device
            runOneSRCCycle(ram, 0, 0L, SRC.toLong())
            assertThat(ram.srcDetected).isTrue()
            runOneIoWriteCycle(ram, 1, ioData, WMP.toLong())
            assertThat(ioBus.value).isEqualTo(ioData)
        }
        @Test
        fun IoRead() {
            // You should not be able to read a RAM's IO bus
            ram.reset()
            // Sync the device
            runOneCycle(ram, 0)
            runOneCycle(ram, 0)
            val ioData:Long = 0xB

            // Write the ioData to the ioBus
            ioBus.write(ioData)

            // Run the SRC command first to arm the device
            runOneSRCCycle(ram, 0, 0L, SRC.toLong())
            assertThat(ram.srcDetected).isTrue()
            val res = romram.runOneIOReadCycle(ram, 0, RDR.toLong())
            assertThat(res.second).isNotEqualTo(ioData.toByte())
        }
        @Test
        fun SrcDecodes() {
            ram.reset()
            // Sync the device
            runOneCycle(ram, 0)
            runOneCycle(ram, 0)

            var chipId = 0L
            var registerSel = 0L
            var characterSel = 0L

            // Verify the defaults work
            runOneSRCCycle(ram, 0, chipId.shl(2).or(registerSel).shl(4).or(characterSel), SRC.toLong())
            assertThat(ram.srcDetected).isTrue()
            assertThat(ram.srcDeviceID).isEqualTo(chipId)
            assertThat(ram.srcRegisterSel).isEqualTo(registerSel)
            assertThat(ram.srcCharacterSel).isEqualTo(characterSel)

            // The chip should not respond to chipId 1
            chipId = 1L
            runOneSRCCycle(ram, 0, chipId.shl(2).or(registerSel).shl(4).or(characterSel), SRC.toLong())
            assertThat(ram.srcDetected).isFalse()
            assertThat(ram.srcDeviceID).isEqualTo(chipId)
            assertThat(ram.srcRegisterSel).isEqualTo(registerSel)
            assertThat(ram.srcCharacterSel).isEqualTo(characterSel)

            // Check the register select decode
            chipId = 0L
            registerSel = 1L
            runOneSRCCycle(ram, 0, chipId.shl(2).or(registerSel).shl(4).or(characterSel), SRC.toLong())
            assertThat(ram.srcDetected).isTrue()
            assertThat(ram.srcDeviceID).isEqualTo(chipId)
            assertThat(ram.srcRegisterSel).isEqualTo(registerSel)
            assertThat(ram.srcCharacterSel).isEqualTo(characterSel)

            // And finally, the character select decode
            characterSel = 0x7
            runOneSRCCycle(ram, 0, chipId.shl(2).or(registerSel).shl(4).or(characterSel), SRC.toLong())
            assertThat(ram.srcDetected).isTrue()
            assertThat(ram.srcDeviceID).isEqualTo(chipId)
            assertThat(ram.srcRegisterSel).isEqualTo(registerSel)
            // We need one more clock to get the srcCharacterSel, but we don't want to mess up the sync timing, so run
            // a whole cycle
            runOneCycle(ram, 0)
            assertThat(ram.srcCharacterSel).isEqualTo(characterSel)
        }
        @Test
        fun CharacterWrite() {
            ram.reset()
            // Sync the device
            runOneCycle(ram, 0)
            runOneCycle(ram, 0)
            var data = mutableListOf<Long>()
            for (i in 0L..15L) {
                data.add(i)
            }

            // Write each character into the RAM
            for (i in 0L..15L) {
                // Run the SRC command first to arm the device
                // The data is the character select
                runOneSRCCycle(ram, 0, i, SRC.toLong())
                assertThat(ram.srcDetected).isTrue()
                runOneIoWriteCycle(ram, 0, i, WRM.toLong())
                assertThat(ram.srcCharacterSel).isEqualTo(i)
            }
            // Finally, go directly read the RAM data
            for (i in 0L..15L) {
                assertThat(ram.data[i.toInt()].toLong()).isEqualTo(data[i.toInt()])
            }
        }
        @Test
        fun CharacterRead() {
            ram.reset()
            // Sync the device
            runOneCycle(ram, 0)
            runOneCycle(ram, 0)
            var data = mutableListOf<Long>()
            for (i in 0L..15L) {
                data.add(i)
            }

            // Write each character into the RAM
            for (i in 0L..15L) {
                // Run the SRC command first to arm the device
                // The data is the character select
                runOneSRCCycle(ram, 0, i, SRC.toLong())
                assertThat(ram.srcDetected).isTrue()
                runOneIoWriteCycle(ram, 0, i, WRM.toLong())
                assertThat(ram.srcCharacterSel).isEqualTo(i)
            }
            // Finally, read the data back using the RDM command
            for (i in 0L..15L) {
                // Run the SRC command first to arm the device
                // The data is the character select
                runOneSRCCycle(ram, 0, i, SRC.toLong())
                assertThat(ram.srcDetected).isTrue()
                val res = runOneIOReadCycle(ram, 0, RDM.toLong())
                assertThat(res.second.toLong()).isEqualTo(data[i.toInt()])
            }
        }

    }
}