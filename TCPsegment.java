import java.nio.ByteBuffer;

public class TCPsegment {

  public static final byte SYN = 0x4;
  public static final byte FIN = 0x2;
  public static final byte ACK = 0x1;
  public static final int headerLength = 24; // headerlength is always 24 bytes

  protected byte flag;
  protected int sequenceNum;
  protected int acknowledgement;
  protected double timestamp;
  protected int length;
  protected short checksum;
  protected byte[] data;
  protected int totalLength;
  
  public TCPsegment(byte flag, byte sequenceNum, byte acknowledgement, byte timestamp, byte length, byte[] data) {
    this.flag = flag;
    this.sequenceNum = sequenceNum;
    this.acknowledgement = acknowledgement;
    this.timestamp = timestamp;
    this.length = length;
    this.checksum = 0;
    this.data = data;
    this.totalLength = data.length + headerLength;
  }

  public TCPsegment(byte flag, byte sequenceNum) {
    this.flag = flag;
    this.sequenceNum = sequenceNum;
    this.data = null;
    this.checksum = 0;
  }

  public byte[] serialize() {
    byte[] serialized = new byte[this.totalLength];
    ByteBuffer bb = ByteBuffer.wrap(serialized);
    bb.putInt(this.sequenceNum);
    bb.putInt(this.acknowledgement);
    bb.putDouble(this.timestamp);
    bb.putInt((this.length & (1 << 29) - 1) << 3 + this.flag);
    bb.putShort((short) 0);
    bb.putShort(this.checksum);
    if (this.data != null)
      bb.put(data);

    // consult code from assign2 TCP checksum calculation
    if (this.checksum == 0) {
      bb.rewind();
      int accumulation = 0;
      for (int i = 0; i < headerLength / 2; ++i) {
          accumulation += 0xffff & bb.getShort();
      }
      accumulation = ((accumulation >> 16) & 0xffff)
              + (accumulation & 0xffff);
      this.checksum = (short) (~accumulation & 0xffff);
      // bb.putShort(16, (short) 0);
      bb.putShort(18, this.checksum);
    }

    return serialized;
  }

}
