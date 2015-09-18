/*
 * Copyright 2015 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.termd.core.tty;

import io.termd.core.telnet.TestBase;
import io.termd.core.util.Helper;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public abstract class TtyTestBase extends TestBase {

  protected abstract void assertConnect(String term) throws Exception;

  protected abstract void assertWrite(byte... data) throws Exception;

  protected abstract String assertReadString(int len) throws Exception;

  protected abstract void assertWriteln(String s) throws Exception;

  protected abstract void server(Consumer<TtyConnection> onConnect);

  protected abstract void resize(int width, int height);

  protected void assertConnect() throws Exception {
    assertConnect(null);
  }

  protected final void assertWrite(int... codePoints) throws Exception {
    assertWrite(Helper.fromCodePoints(codePoints));
  }

  protected final void assertWrite(String s) throws Exception {
    assertWrite(s.getBytes("UTF-8"));
  }

  @Test
  public void testWrite() throws Exception {
    final AtomicInteger requestCount = new AtomicInteger();
    server(conn -> {
      requestCount.incrementAndGet();
      conn.stdoutHandler().accept(new int[]{'%', ' '});
    });
    assertConnect();
    assertEquals("% ", assertReadString(2));
    assertEquals(1, requestCount.get());
  }

  @Test
  public void testRead() throws Exception {
    final ArrayBlockingQueue<int[]> queue = new ArrayBlockingQueue<>(1);
    server(conn -> conn.setStdinHandler(data -> {
      queue.add(data);
      conn.stdoutHandler().accept(new int[]{'h', 'e', 'l', 'l', 'o'});
    }));
    assertConnect();
    assertWriteln("");
    int[] data = queue.poll(10, TimeUnit.SECONDS);
    assertTrue(Arrays.equals(new int[]{'\r'}, data));
    assertEquals("hello", assertReadString(5));
  }

  @Test
  public void testSignalInterleaving() throws Exception {
    StringBuilder buffer = new StringBuilder();
    AtomicInteger count = new AtomicInteger();
    server(conn -> {
      conn.setStdinHandler(event -> Helper.appendCodePoints(event, buffer));
      conn.setEventHandler((event, cp) -> {
        if (event == TtyEvent.INTR) {
          switch (count.get()) {
            case 0:
              assertEquals("hello", buffer.toString());
              buffer.setLength(0);
              count.set(1);
              break;
            case 1:
              assertEquals("bye", buffer.toString());
              count.set(2);
              testComplete();
              break;
            default:
              fail("Not expected");
          }
        }
      });
    });
    assertConnect();
    assertWrite('h', 'e', 'l', 'l', 'o', 3, 'b', 'y', 'e', 3);
    await();
  }

  @Test
  public void testSignals() throws Exception {
    StringBuilder buffer = new StringBuilder();
    AtomicInteger count = new AtomicInteger();
    server(conn -> {
      conn.setStdinHandler(event -> Helper.appendCodePoints(event, buffer));
      conn.setEventHandler((event, cp) -> {
        switch (count.get()) {
          case 0:
            assertEquals(TtyEvent.INTR, event);
            count.set(1);
            break;
          case 1:
            assertEquals(TtyEvent.EOF, event);
            count.set(2);
            break;
          case 2:
            assertEquals(TtyEvent.SUSP, event);
            count.set(3);
            testComplete();
            break;
          default:
            fail("Not expected");
        }
      });
    });
    assertConnect();
    assertWrite(3, 4, 26);
    await();
  }

  /*
    @Test
    public void testAsyncEndRequest() throws Exception {
      final ArrayBlockingQueue<ReadlineRequest> requestContextWait = new ArrayBlockingQueue<>(1);
      server(new Provider<TelnetHandler>() {
        @Override
        public TelnetHandler provide() {
          return new TelnetTermConnection() {
            @Override
            protected void onOpen(TelnetConnection conn) {
              super.onOpen(conn);
              new ReadlineTerm(this, new Handler<ReadlineRequest>() {
                @Override
                public void handle(ReadlineRequest request) {
                  switch (request.requestCount()) {
                    case 0:
                      request.write("% ").end();
                      break;
                    default:
                      requestContextWait.add(request);
                  }
                }
              });
            }
          };
        }
      });
      assertConnect();
      assertEquals("% ", assertReadString(2));
      assertWriteln("");
      ReadlineRequest requestContext = assertNotNull(requestContextWait.poll(10, TimeUnit.SECONDS));
      assertEquals("\r\n", assertReadString(2));
      requestContext.write("% ").end();
      assertEquals("% ", assertReadString(2));
    }
  */
  @Test
  public void testBufferedRead() throws Exception {
    AtomicInteger count = new AtomicInteger();
    final CountDownLatch latch = new CountDownLatch(1);
    server(conn -> {
      conn.setEventHandler((event, cp) -> conn.setStdinHandler(codePoints -> {
        switch (count.getAndIncrement()) {
          case 0:
            assertEquals("hello", Helper.fromCodePoints(codePoints));
            latch.countDown();
            break;
          case 1:
            assertEquals("bye", Helper.fromCodePoints(codePoints));
            testComplete();
            break;
          default:
            fail("Too many requests");
        }
      }));
    });
    assertConnect();
    assertWrite("hello");
    assertWrite(3);
    await(latch);
    assertWrite("bye");
    await();
  }

  @Test
  public void testTerminalType() throws Exception {
    server(conn -> conn.setTermHandler(event -> {
      assertEquals("xterm", event);
      testComplete();
    }));
    assertConnect("xterm");
    assertWrite("bye");
    await();
  }

  @Test
  public void testSize() throws Exception {
    server(conn -> {
          if (conn.size() != null) {
            assertEquals(80, conn.size().x());
            assertEquals(24, conn.size().y());
            testComplete();
          } else {
            conn.setSizeHandler(size -> {
              assertEquals(80, conn.size().x());
              assertEquals(24, conn.size().y());
              testComplete();
            });
          }
        }
    );
    assertConnect();
    await();
  }

  @Test
  public void testConnectionClose() throws Exception {
    AtomicInteger closeCount = new AtomicInteger();
    server(conn -> {
      conn.setCloseHandler(v -> {
        if (closeCount.incrementAndGet() > 1) {
          fail("Closed call several times");
        } else {
          testComplete();
        }
      });
      conn.setStdinHandler(text -> conn.close());
    });
    assertConnect();
    assertWrite("bye");
    await();
  }
}