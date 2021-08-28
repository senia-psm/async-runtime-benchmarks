package benchmarks

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(value = 3, jvmArgsAppend = Array("-Dcats.effect.tracing.mode=none"))
@Threads(value = 1)
class Benchmarks {

  val catsEffectRuntime = cats.effect.unsafe.implicits.global

  val zioPlatform = zio.internal.Platform
    .makeDefault(1024)
    .withReportFailure(_ => ())
    .withTracing(zio.internal.Tracing.disabled)
  val zioRuntime = zio.Runtime.unsafeFromLayer(zio.ZEnv.live, zioPlatform)

  @Benchmark
  def catsEffect3RuntimePingPong(): Int = {
    import cats.effect.{Deferred, IO}
    import cats.effect.std.Queue

    def catsEffectPingPong(): Int = {

      def catsEffectRepeat[A](n: Int)(io: IO[A]): IO[A] =
        if (n <= 1) io
        else io.flatMap(_ => catsEffectRepeat(n - 1)(io))

      def iterate(deferred: Deferred[IO, Unit], n: Int): IO[Any] =
        for {
          ref <- IO.ref(n)
          queue <- Queue.bounded[IO, Unit](1)
          effect = queue.offer(()).start >>
            queue.take >>
            ref
              .modify(n =>
                (n - 1, if (n == 1) deferred.complete(()) else IO.unit)
              )
              .flatten
          _ <- catsEffectRepeat(1000)(effect.start)
        } yield ()

      val io = for {
        deferred <- IO.deferred[Unit]
        _ <- iterate(deferred, 1000).start
        _ <- deferred.get
      } yield 0

      runCatsEffect3(io)
    }

    catsEffectPingPong()
  }


  // we insert leading yields for both runtimes to remove the "synchronous prefix" optimization

  private[this] def runCatsEffect3[A](io: cats.effect.IO[A]): A =
    (cats.effect.IO.cede.flatMap(_ => io)).unsafeRunSync()(catsEffectRuntime)

  private[this] def runZIO[A](io: zio.UIO[A]): A =
    zioRuntime.unsafeRun(zio.UIO.yieldNow.flatMap(_ => io))
}
