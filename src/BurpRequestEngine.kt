package burp
import java.net.URL
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

class BurpRequestEngine(url: String, threads: Int, val callback: (String, String) -> Boolean): RequestEngine() {

    private val threadPool = ArrayList<Thread>()
    private val requestQueue = ArrayBlockingQueue<Request>(1000000)
    private val baseline = BurpExtender.callbacks.helpers.analyzeResponseVariations()
    private val baselinelock = ReentrantReadWriteLock()


    init {
        completedLatch = CountDownLatch(threads)
        println("Warming up...")
        val target = URL(url)
        val service = BurpExtender.callbacks.helpers.buildHttpService(target.host, target.port, true)

        for(j in 1..threads) {
            threadPool.add(
                    thread {
                        sendRequests(service)
                    }
            )
        }
    }

    override fun start(timeout: Int) {
        attackState.set(1)
        start = System.nanoTime()
    }

    override fun queue(req: String) {
        queue(req, null, false)
    }

    fun queue(template: String, payload: String?) {
        queue(template, payload, false)
    }

    fun queue(template: String, payload: String?, learnBoring: Boolean?) {

        val request = Request(template.replace("Connection: keep-alive", "Connection: close"), payload, learnBoring ?: false)

        val queued = requestQueue.offer(request, 10, TimeUnit.SECONDS)
        if (!queued) {
            println("Timeout queuing request. Aborting.")
            this.showStats(1)
        }
    }

    private fun sendRequests(service: IHttpService) {
        while(attackState.get()<1) {
            Thread.sleep(10)
        }

        while(!BurpExtender.unloaded) {
            val req = requestQueue.poll(100, TimeUnit.MILLISECONDS);

            if(req == null) {
                if (attackState.get() == 2) {
                    completedLatch.countDown()
                    return
                }
                else {
                    continue
                }
            }

            val resp = BurpExtender.callbacks.makeHttpRequest(service, req.getRequest().toByteArray(Charsets.ISO_8859_1))
            if (resp.response != null) {
                successfulRequests.getAndIncrement()
                processResponse(req, resp.response)
                callback(req.getRequest(), String(resp.response))
            }
            else {
                print("null response :(")
            }
        }
    }

    // todo move this into the parent class
    private fun processResponse(req: Request, response: ByteArray): Boolean {
        if (req.learnBoring) {

            baselinelock.writeLock().lock()
            baseline.updateWith(response)
            baselinelock.writeLock().unlock()
        }
        else {
            val resp = BurpExtender.callbacks.helpers.analyzeResponseVariations(response)

            baselinelock.readLock().lock()
            val invariants = baseline.invariantAttributes
            baselinelock.readLock().unlock()

            for(attribute in invariants) {
                if (baseline.getAttributeValue(attribute, 0) != resp.getAttributeValue(attribute, 0)) {
                    println("Interesting: "+req.word)
                    return true
                }
            }
        }

        return false
    }

}