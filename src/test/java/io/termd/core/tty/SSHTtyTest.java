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

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import io.termd.core.ssh.SshTtyConnection;
import io.termd.core.ssh.vertx.VertxIoServiceFactoryFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class SshTtyTest extends TtyTestBase {

  JSch jsch = new JSch();
  Session session;
  ChannelShell channel;
  InputStream in;
  OutputStream out;

  @Override
  protected void assertConnect(String term) throws Exception {
    if (session != null) {
      throw failure("Already a session");
    }
    session = jsch.getSession("whatever", "localhost", 5000);
    session.setPassword("whocares");
    session.setUserInfo(new UserInfo() {
      @Override
      public String getPassphrase() {
        return null;
      }

      @Override
      public String getPassword() {
        return null;
      }

      @Override
      public boolean promptPassword(String s) {
        return false;
      }

      @Override
      public boolean promptPassphrase(String s) {
        return false;
      }

      @Override
      public boolean promptYesNo(String s) {
        return true;
      } // Accept all server keys

      @Override
      public void showMessage(String s) {
      }
    });
    session.connect();
    channel = (ChannelShell) session.openChannel("shell");
    if (term != null) {
      channel.setPtyType(term);
    }
    channel.connect();
    in = channel.getInputStream();
    out = channel.getOutputStream();
  }

  @Override
  protected void resize(int width, int height) {
    channel.setPtySize(width, height, width * 8, height * 8);
  }

  @Override
  protected void assertWrite(byte... data) throws Exception {
    out.write(data);
    out.flush();
  }

  @Override
  protected String assertReadString(int len) throws Exception {
    byte[] buf = new byte[len];
    while (len > 0) {
      int count = in.read(buf, buf.length - len, len);
      if (count == -1) {
        throw failure("Could not read enough");
      }
      len -= count;
    }
    return new String(buf, "UTF-8");
  }

  @Override
  protected void assertWriteln(String s) throws Exception {
    assertWrite((s + "\r").getBytes("UTF-8"));
  }

  private SshServer sshd;

  @Override
  protected void server(Consumer<TtyConnection> onConnect) {
    if (sshd != null) {
      throw failure("Already a server");
    }
    try {
      sshd = SshServer.setUpDefaultServer();
      sshd.setPort(5000);
      sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(new File("hostkey.ser").toPath()));
      sshd.setPasswordAuthenticator((username, password, session) -> true);
      sshd.setShellFactory(() -> new SshTtyConnection(onConnect));
      sshd.start();
    } catch (Exception e) {
      throw failure(e);
    }
  }

  @After
  public void after() {
    if (out != null) {
      try { out.close(); } catch (Exception ignore) {}
    }
    if (in != null) {
      try { in.close(); } catch (Exception ignore) {}
    }
    if (channel != null) {
      try { channel.disconnect(); } catch (Exception ignore) {}
    }
    if (session != null) {
      try { session.disconnect(); } catch (Exception ignore) {}
    }
    if (sshd != null && !sshd.isClosed()) {
      try {
        sshd.close();
      } catch (Exception ignore) {
      }
    }
  }

  @Test
  public void testFoo() throws Exception {

    sshd = SshServer.setUpDefaultServer();
    sshd.setIoServiceFactoryFactory(new VertxIoServiceFactoryFactory());
    sshd.setPort(5000);
    sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(new File("hostkey.ser").toPath()));
    sshd.setPasswordAuthenticator((username, password, session) -> true);
    sshd.setShellFactory(() -> {
      throw new UnsupportedOperationException("todo");
    });
    sshd.start();


    assertConnect();
    await();


  }
}
