import java.net.InetAddress;
import java.net.UnknownHostException;

public class TCPend {
  // protected int port;
  // protected int remoteIP;
  // protected int remotePort;
  // protected String fileName;
  // protected byte mtu; // maximum transmission unit in bytes
  // protected int sws; // sliding window size
  // protected int mode; // 1 for sender and 0 for receiver

  public static void main(String[] args) {
    int port;
    InetAddress remoteIP = null;
    int remotePort;
    String fileName;
    byte mtu; // maximum transmission unit in bytes
    int sws; // sliding window size
    int mode; // 1 for sender and 0 for receiver

    if (args.length <= 2)
      System.exit(1);

    port = Integer.parseInt(args[1]);

    // TCP sender
    if (args[2].equals("-s")) {
      try {
        remoteIP = InetAddress.getByName(args[3]);
      } catch (UnknownHostException e) {
        System.out.println("unknown IP address.");
        e.printStackTrace();
      }
      remotePort = Integer.parseInt(args[5]);
      fileName = args[7];
      mtu = (byte)Integer.parseInt(args[9]);
      sws = Integer.parseInt(args[11]);
      mode = 1;

      TCPsender sender = new TCPsender(port, remoteIP, remotePort, fileName, mtu, sws);
      sender.printInfo();
    }

    // TCP receiver
    else {
      System.out.println(args[2]);
      mtu = (byte)Integer.parseInt(args[3]);
      sws = Integer.parseInt(args[5]);
      fileName = args[7];
      mode = 0;

      TCPreceiver receiver = new TCPreceiver(port, fileName, mtu, sws);
      receiver.printInfo();
    } 
    
  }
}