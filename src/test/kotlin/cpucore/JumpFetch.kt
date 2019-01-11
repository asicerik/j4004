package cpucore

import common.Bus
import io.reactivex.Emitter
import io.reactivex.Observable
import io.reactivex.observables.ConnectableObservable
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JumpFetchTests {
    val core: CpuCore
    var dataBus = Bus()
    var clk: ConnectableObservable<Int>

    // Set everything up
    init {
        dataBus.init(4, "Test Bus")
        clk = Observable.create { it: Emitter<Int> ->
            emitter = it
        }.publish()
        clk.connect()
        core = CpuCore(dataBus, clk)
    }

    @Nested
    inner class JumpTests {
        @Test
        fun JUN() {
            core.reset()
            Assertions.assertThat(core.sync.clocked).isEqualTo(1)
            var res = waitForSync(core)
            Assertions.assertThat(res.first).isEqualTo(true)
            verifyJumpExtended(core, JMS.toLong(), true, true)
            var nextAddr = 0xabdL
            for (i in 0..3) {
                var addr = runOneCycle(core, NOP.toLong())
                assertThat(addr).isEqualTo(nextAddr)
                nextAddr++
            }
        }
    }
}