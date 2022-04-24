import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.LinkedBlockingQueue;

public class TCPreceiver {

  protected int port;
  protected InetAddress remoteIP;
  protected int remotePort;
  protected String fileName;
  protected int MTU; // maximum transmission unit in bytes
  protected int sws; // sliding window size
  protected int mode; // 1 for sender and 0 for receiver

  protected long startTime = System.nanoTime();

  protected DatagramSocket socket;
  protected FileOutputStream fos;
  protected File outFile;

  protected LinkedBlockingQueue<TCPsegment> slidingWindow;
  protected Integer ackedSeqNum;
  // protected int writtenSeqNum;
  protected int sequenceNum;
  protected int curSeqNum;
  protected int curSegIdx;
  protected Integer curAckedSegIdx;

  class Producer implements Runnable {
    protected final LinkedBlockingQueue<TCPsegment> queue;

    public Producer(LinkedBlockingQueue<TCPsegment> queue) {
      this.queue = queue;
    }

    @Override
    public void run() {
      try {
        this.produce();
      } catch (IOException e) {
        e.printStackTrace();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    public void produce() throws IOException, InterruptedException {
      while (true) {
        // client receives data package from server
        byte[] tcpBuf = new byte[MTU + TCPsegment.headerLength];
        DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length);
        socket.receive(dataPacket);
        TCPsegment dataTCP = new TCPsegment();
        dataTCP = dataTCP.deserialize(dataPacket.getData(), 0, tcpBuf.length); // dataBuf.length mismatch
        // output data tcp received
        dataTCP.setTime(System.nanoTime() - startTime);
        dataTCP.printInfo(false);

        // client drops dataTCP out of current sliding window
        if (dataTCP.getSequenceNum() > ackedSeqNum + (sws+1) * MTU || dataTCP.getSequenceNum() < ackedSeqNum) {
          System.out.println("seqNum: " + dataTCP.getSequenceNum() + " ackedSeqNum: " + ackedSeqNum);
          System.out.println("Sliding window from: " + ackedSeqNum + " to: " + (ackedSeqNum + (sws+1) * MTU));
          continue;
        }

        // check if data packet is valid
        if (dataTCP.getData() != null && dataTCP.getLength() != dataTCP.getData().length) {
          System.out.println("drop once");
          continue;
        }
          
        if (dataTCP.getChecksum() != dataTCP.deserialize(dataTCP.serialize(), 0, dataTCP.length).getChecksum()) {
          // drop packet
          System.out.println("drop once");
          continue;
        }
          
        this.queue.add(dataTCP);

        // check if client receives first FIN from server
        if (dataTCP.getFlag() == TCPsegment.FIN + TCPsegment.ACK) {
          break;
        }
      }
    }
  }

  // Consumer is responsible for writing data to file and update ackedSeqNum
  class Consumer implements Runnable {
    protected final LinkedBlockingQueue<TCPsegment> queue;

    public Consumer(LinkedBlockingQueue<TCPsegment> queue) {
      this.queue = queue;
    }

    @Override
    public void run() {
      try {
        synchronized (queue) {
          this.consume();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    public void consume() throws IOException {
      ackedSeqNum = 1;
      while (true) {
        TCPsegment dataTCP = null;
        try {
          dataTCP = this.queue.take();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        if (dataTCP == null)
          System.out.println("Consumer get no TCP segments.");

        if (dataTCP.getSequenceNum() == ackedSeqNum) {
          if (dataTCP.getData() == null)
            System.out.println("dataTCP.getData() is null");
          byte[] dataBuf = dataTCP.getData();
          fos.write(dataBuf);
          // update acknowledgement number
          ackedSeqNum += dataTCP.getLength();
        }

        // check if client receives first FIN from server
        if (dataTCP.getFlag() == TCPsegment.FIN + TCPsegment.ACK) {
          // clients sends out second FIN + ACK to server
          TCPsegment secondFINACKTCP = new TCPsegment((byte) (TCPsegment.FIN + TCPsegment.ACK), 1,
              dataTCP.getSequenceNum() + 1, dataTCP.getTimestamp());
          byte[] secondFINACKBuf = secondFINACKTCP.serialize();
          DatagramPacket secondFINACKPacket = new DatagramPacket(secondFINACKBuf, secondFINACKBuf.length, remoteIP,
              remotePort);
          socket.send(secondFINACKPacket);
          // output second FIN + ACK TCP segment sent
          secondFINACKTCP.setTime(System.nanoTime() - startTime);
          secondFINACKTCP.printInfo(true);
          break;
        }

        // clients sends out acknowledgement to server
        TCPsegment ackTCP = new TCPsegment(TCPsegment.ACK, 1, ackedSeqNum, dataTCP.getTimestamp());
        byte[] ackBuf = ackTCP.serialize();
        DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length, remoteIP, remotePort);
        socket.send(ackPacket);
        // output acknowledgement TCP segment sent
        ackTCP.setTime(System.nanoTime() - startTime);
        ackTCP.printInfo(true);
      }
    }
  }

  public TCPreceiver(int port, String fileName, int MTU, int sws) {
    this.port = port;
    this.fileName = fileName;
    this.MTU = MTU - 20 - 8 - 24;
    this.sws = sws;

    try {
      printInfo();

      // client receives initial SYN from server
      socket = new DatagramSocket(port);
      byte[] buf = new byte[TCPsegment.headerLength];
      DatagramPacket initialPacket = new DatagramPacket(buf, TCPsegment.headerLength);
      // System.out.println("Wait fot server SYN....");
      socket.receive(initialPacket);
      TCPsegment initialTCP = new TCPsegment();
      initialTCP = initialTCP.deserialize(initialPacket.getData(), 0, buf.length);
      // output first SYN received
      initialTCP.setTime(System.nanoTime() - this.startTime);
      initialTCP.printInfo(false);

      // client sends subsequent SYN + ACK back to server
      // int acknowledgement = initialTCP.getSequenceNum() + 1;
      long acknowledgedTimestamp = initialTCP.getTimestamp();
      TCPsegment secondTCP = new TCPsegment((byte) (TCPsegment.SYN + TCPsegment.ACK), 0,
          initialTCP.getSequenceNum() + 1, acknowledgedTimestamp);
      byte[] secondBuf = secondTCP.serialize();
      InetAddress serverIP = initialPacket.getAddress();
      int serverPort = initialPacket.getPort();
      DatagramPacket secondPacket = new DatagramPacket(secondBuf, secondBuf.length, serverIP, serverPort);
      socket.send(secondPacket);
      // output second SYN + ACK sent
      secondTCP.setTime(System.nanoTime() - this.startTime);
      secondTCP.printInfo(true);

      // client receives final ACK
      byte[] finalBuf = new byte[TCPsegment.headerLength];
      DatagramPacket finalPacket = new DatagramPacket(finalBuf, TCPsegment.headerLength);
      socket.receive(finalPacket);
      TCPsegment finalTCP = new TCPsegment();
      finalTCP = finalTCP.deserialize(finalPacket.getData(), 0, finalBuf.length);
      // output final ACK received
      finalTCP.setTime(System.nanoTime() - this.startTime);
      finalTCP.printInfo(false);

      // initial sequence number of client set to 0
      if (finalTCP.getAcknowledgement() != 1) {
        System.out.println("Acknowledgement received: " + finalTCP.getAcknowledgement());
        System.out.println("ACK from server confirmation fails.");
        System.exit(1);
      }

      System.out.println("Connection established!!!\n-----Begin data transmission-----");

      outFile = new File(this.fileName);
      if (!outFile.createNewFile()) {
        System.out.println("Output file exists.");
        System.exit(1);
      }
      fos = new FileOutputStream(outFile);

      // set server IP and port up
      this.remoteIP = finalPacket.getAddress();
      this.remotePort = finalPacket.getPort();

      this.ackedSeqNum = 1;

      // // begin data transmission
      // int acknowledgementNum = 1;
      // while (true) {
      //   // client receives data from server
      //   byte[] tcpBuf = new byte[this.MTU + TCPsegment.headerLength];
      //   DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length);
      //   // socket.setSoTimeout(5 * 1000);
      //   socket.receive(dataPacket);
      //   TCPsegment dataTCP = new TCPsegment();
      //   dataTCP = dataTCP.deserialize(dataPacket.getData(), 0, tcpBuf.length); // dataBuf.length mismatch
      //   // output data tcp received
      //   dataTCP.setTime(System.nanoTime() - this.startTime);
      //   dataTCP.printInfo(false);

      //   // check if client receives first FIN from server
      //   if (dataTCP.getFlag() == TCPsegment.FIN + TCPsegment.ACK) {
      //     // clients sends out second FIN + ACK to server
      //     TCPsegment secondFINACKTCP = new TCPsegment((byte) (TCPsegment.FIN + TCPsegment.ACK), 1,
      //         dataTCP.getSequenceNum() + 1, dataTCP.getTimestamp());
      //     byte[] secondFINACKBuf = secondFINACKTCP.serialize();
      //     DatagramPacket secondFINACKPacket = new DatagramPacket(secondFINACKBuf, secondFINACKBuf.length, remoteIP,
      //         remotePort);
      //     socket.send(secondFINACKPacket);
      //     // output second FIN + ACK TCP segment sent
      //     secondFINACKTCP.setTime(System.nanoTime() - this.startTime);
      //     secondFINACKTCP.printInfo(true);
      //     break;
      //   }

      //   // write data received into output file
      //   // drop duplicate data package
      //   if (dataTCP.getSequenceNum() >= acknowledgementNum) {
      //     if (dataTCP.getData() == null)
      //       System.out.println("dataTCP.getData() is null");
      //     byte[] dataBuf = dataTCP.getData();
      //     fos.write(dataBuf);

      //     // update acknowledgement number
      //     acknowledgementNum += dataTCP.getLength();
      //   }

      //   // clients sends out acknowledgement to server
      //   TCPsegment ackTCP = new TCPsegment(TCPsegment.ACK, 1, acknowledgementNum, dataTCP.getTimestamp());
      //   byte[] ackBuf = ackTCP.serialize();
      //   DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length, remoteIP, remotePort);
      //   socket.send(ackPacket);
      //   // output acknowledgement TCP segment sent
      //   ackTCP.setTime(System.nanoTime() - this.startTime);
      //   ackTCP.printInfo(true);
      // }
      slidingWindow = new LinkedBlockingQueue<TCPsegment>(this.MTU);
      Producer p = new Producer(slidingWindow);
      Consumer c = new Consumer(slidingWindow);
      Thread pThread = new Thread(p);
      Thread cThread = new Thread(c);
      pThread.start();
      cThread.start();

      try {
        pThread.join();
        cThread.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      // client receives last ACK from server
      byte[] lastACKBuf = new byte[TCPsegment.headerLength];
      DatagramPacket lastACKPacket = new DatagramPacket(lastACKBuf, lastACKBuf.length);
      socket.receive(lastACKPacket);
      TCPsegment lastACKTCP = new TCPsegment();
      lastACKTCP = lastACKTCP.deserialize(lastACKPacket.getData(), 0, lastACKBuf.length); // dataBuf.length mismatch
      // output last ACK received
      lastACKTCP.setTime(System.nanoTime() - this.startTime);
      lastACKTCP.printInfo(false);

      socket.close();
    } catch (SocketException e) {
      System.out.println("Sender socket error.");
      e.printStackTrace();
    } catch (IOException e) {
      System.out.println("An I/O exception occurs.");
      e.printStackTrace();
    } finally {
      if (socket != null)
        socket.close();
      try {
        if (fos != null)
          fos.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public void printInfo() {
    System.out
        .println("TCP client created with port: " + port + " fileName: " + fileName + " MTU: " + MTU + " sws: " + sws);
  }
}
