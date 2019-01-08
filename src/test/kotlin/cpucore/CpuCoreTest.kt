package cpucore

import common.Bus
import io.reactivex.Emitter
import io.reactivex.Observable
import io.reactivex.observables.ConnectableObservable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

var emitter: Emitter<Int>? = null

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CpuCoreTest {
    val core: CpuCore
    var dataBus = Bus()
    var clk: ConnectableObservable<Int>

    // Set everything up
    init {
        dataBus.init(4,"Test Bus")
        clk = Observable.create { it: Emitter<Int> ->
            emitter = it
        }.publish()
        clk.connect()
        core = CpuCore(dataBus, clk)
    }

    @Nested
    inner class BasicTests {
        @Test
        fun sync() {
            core.reset()
            assertThat(core.sync.clocked).isEqualTo(1)
            var res = waitForSync(core)
            assertThat(res.first).isEqualTo(true)
            assertThat(res.second).isEqualTo(7)
        }
        @Test
        fun programCounter() {
            core.reset()
            val res = waitForSync(core)
            assertThat(res.first).isEqualTo(true)
            for (i in 2..6) {
                val addr = runOneCycle(core, 0)
                assertThat(addr.toInt()).isEqualTo(i)
            }
        }
    }

    @Nested
    inner class Instructions {
        @Test
        fun LDM() {
            core.reset()
            var res = waitForSync(core)
            assertThat(res.first).isEqualTo(true)
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(0L)
            // Load the accumulator
            runOneCycle(core, LDM.toLong().or(0x7))
            // Accumulator should now have 0x7
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(7L)
        }
        @Test
        fun XCH() {
            core.reset()
            var res = waitForSync(core)
            assertThat(res.first).isEqualTo(true)
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(0L)
            // Load the accumulator
            runOneCycle(core, LDM.toLong().or(0x7))
            // Accumulator should now have 0x7
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(7L)
            // Run the XCH cycle into register 2
            runOneCycle(core, XCH.toLong().or(0x2))
            // Accumulator should now have 0x0
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(0L)
            // Check index register 2
            assertThat(core.indexRegisters.readDirect(2)).isEqualTo(7L)
        }
        @Test
        fun LD() {
            core.reset()
            var res = waitForSync(core)
            assertThat(res.first).isEqualTo(true)
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(0L)
            // Write directly to an index register
            core.indexRegisters.writeDirect(5, 9)
            // Load the accumulator
            runOneCycle(core, LD.toLong().or(0x5))
            // Accumulator should now have 0x9
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(9L)
        }
        @Test
        fun INC() {
            core.reset()
            var res = waitForSync(core)
            assertThat(res.first).isEqualTo(true)
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(0L)
            // Write directly to an index register
            core.indexRegisters.writeDirect(5, 9)
            // Run the increment
            runOneCycle(core, INC.toLong().or(0x5))
            // The register should now have 10
            assertThat(core.indexRegisters.readDirect(5)).isEqualTo(10L)
        }
        @Test
        fun ADD() {
            core.reset()
            var res = waitForSync(core)
            assertThat(res.first).isEqualTo(true)
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(0L)
            val valA = 9L
            val valB = 6L
            // Write directly to an index register
            core.indexRegisters.writeDirect(5, valA)
            // Write the other operand to the accumulator
            // Load the accumulator
            runOneCycle(core, LDM.toLong().or(valB))
            // Run the add
            runOneCycle(core, ADD.toLong().or(0x5))

            // Accumulator should now have the sum
            val expVal = valA + valB
            assertThat(core.aluCore.accum.readDirect()).isEqualTo(expVal)
        }
    }
}