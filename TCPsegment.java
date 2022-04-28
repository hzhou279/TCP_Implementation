import java.nio.ByteBuffer;

public class TCPsegment {

  public static final byte SYN = 0x4;
  public static final byte FIN = 0x2;
  public static final byte ACK = 0x1;
  public static final int headerLength = 24; // headerlength is always 24 bytes

  protected byte flag;
  protected int sequenceNum;
  protected int acknowledgement;
  protected long timestamp;
  protected int length;
  protected short checksum;
  protected byte[] data;
  protected int totalLength;
  protected double time;

  public TCPsegment(byte flag, int sequenceNum, int acknowledgement, long timestamp, int length, byte[] data) {
    this.flag = flag;
    this.sequenceNum = sequenceNum;
    this.acknowledgement = acknowledgement;
    this.timestamp = timestamp;
    this.length = length;
    this.checksum = 0;
    this.data = data;
    this.totalLength = data.length + headerLength;
  }

  public TCPsegment(byte flag, int sequenceNum, long timestamp) {
    this.flag = flag;
    this.sequenceNum = sequenceNum;
    this.acknowledgement = 0;
    this.timestamp = timestamp;
    this.length = 0;
    this.checksum = 0;
    this.data = null;
    this.totalLength = headerLength;
  }

  public TCPsegment(byte flag, int sequenceNum, int acknowledgement, long timestamp) {
    this.flag = flag;
    this.sequenceNum = sequenceNum;
    this.acknowledgement = acknowledgement;
    this.timestamp = timestamp;
    this.length = 0;
    this.checksum = 0;
    this.data = null;
    this.totalLength = headerLength;
  }

  public TCPsegment() {
    this.flag = 0;
    this.sequenceNum = 0;
    this.acknowledgement = 0;
    this.timestamp = 0;
    this.length = 0;
    this.checksum = 0;
    this.data = null;
    this.totalLength = headerLength;
  }

  public byte[] serialize() {
    // if (this.totalLength == 0)
    //   this.totalLength = TCPsegment.headerLength + this.data.length;
    byte[] serialized = new byte[this.totalLength];
    ByteBuffer bb = ByteBuffer.wrap(serialized);
    bb.putInt(this.sequenceNum);
    bb.putInt(this.acknowledgement);
    bb.putLong(this.timestamp);
    bb.putInt((((this.length & (1 << 29) - 1)) << 3) + this.flag);
    bb.putShort((short) 0);
    bb.putShort(this.checksum);
    if (this.data != null) {
      // System.out.println("\n" + this.data.length + "\n");
      bb.put(this.data);
    }
    
    // consult code from assign2 TCP checksum calculation
    if (this.checksum == (short)0) {
      bb.rewind();
      int accumulation = 0;
      for (int i = 0; i < headerLength / 2; ++i) {
        accumulation += 0xffff & bb.getShort();
      }
      accumulation = ((accumulation >> 16) & 0xffff)
          + (accumulation & 0xffff);
      this.checksum = (short) (~accumulation & 0xffff);
      // bb.putShort(16, (short) 0);
      bb.putShort(22, this.checksum);
    }

    return serialized;
  }

  public TCPsegment deserialize(byte[] data, int offset, int length) {
    // ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
    ByteBuffer bb = ByteBuffer.wrap(data);

    this.sequenceNum = bb.getInt();
    this.acknowledgement = bb.getInt();
    this.timestamp = bb.getLong();
    int temp = bb.getInt();
    this.flag = (byte) (temp & (1 << 3) - 1);
    this.length = temp >> 3;
    bb.getShort();
    this.checksum = bb.getShort();
    if (this.length != 0) {
      this.data = new byte[this.length];
      bb.get(this.data, 0, this.length);
      this.totalLength = TCPsegment.headerLength + this.length;
    } else {
      this.data = null;
      this.totalLength = TCPsegment.headerLength;
    }
      

    // for debug use
    // System.out.println("deserialize output: ");
    // System.out.println("sequence number: " + sequenceNum);
    // System.out.println("acknowledgement: " + acknowledgement);
    // System.out.println("timestamp: " + timestamp);
    // System.out.println("length + flag: " + temp);
    // System.out.println("flag: " + flag);
    // System.out.println("length: " + this.length);

    return this;
  }

  public void printInfo(boolean sndOrRcv) {
    String[] flagList = new String[4];
    for (int i = 0; i < 4; i++)
      flagList[i] = "-";
    if (this.flag == TCPsegment.SYN)
      flagList[0] = "S";
    else if (this.flag == TCPsegment.ACK)
      flagList[1] = "A";
    else if (this.flag == TCPsegment.SYN + TCPsegment.ACK) {
      flagList[0] = "S";
      flagList[1] = "A";
    }
    else if (this.flag == TCPsegment.FIN + TCPsegment.ACK) {
      flagList[2] = "F";
      flagList[1] = "A";
    }
    else
      flagList[2] = "F";
    if (this.data != null)
      flagList[3] = "D";
    String output = "";
    if (sndOrRcv)
      output += "snd ";
    else
      output += "rcv ";
    output += String.format("%.2f", this.time) + " ";
    for (int i = 0; i < 4; i++)
      output += flagList[i] + " ";
    output += this.sequenceNum + " ";
    if (this.data != null)
      output += this.data.length + " ";
    else
      output += "0 ";
    output += this.acknowledgement;
    System.out.println(output);
  }

  // possible use of getter and setter methods
  public byte getFlag() {
    return this.flag;
  }

  public void setFlag(byte flag) {
    this.flag = flag;
  }

  public int getSequenceNum() {
    return this.sequenceNum;
  }

  public void setSequenceNum(int sequenceNum) {
    this.sequenceNum = sequenceNum;
  }

  public int getAcknowledgement() {
    return this.acknowledgement;
  }

  public void setAcknowledgement(int acknowledgement) {
    this.acknowledgement = acknowledgement;
  }

  public long getTimestamp() {
    return this.timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  public int getLength() {
    return this.length;
  }

  public void setLength(int length) {
    this.length = length;
  }

  public short getChecksum() {
    return this.checksum;
  }

  public void setChecksum(short checksum) {
    this.checksum = checksum;
  }

  public byte[] getData() {
    return this.data;
  }

  public void setData(byte[] data) {
    this.data = data;
  }

  public int getTotalLength() {
    return this.totalLength;
  }

  public void setTotalLength(int totalLength) {
    this.totalLength = totalLength;
  }

  public double getTime() {
    return this.time;
  }

  public void setTime(long time) {
    this.time = time / Math.pow(10, 9);
  }
}
