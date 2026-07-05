namespace MotionMouse.Protocol;

/// <summary>
/// Packet type byte constants matching PROTOCOL.md exactly.
/// These values are part of the wire format and must never change
/// without a protocol version bump.
/// </summary>
public static class PacketType
{
    public const byte Hello         = 0x01;
    public const byte HelloAck      = 0x02;
    public const byte Motion        = 0x03;
    public const byte Button        = 0x04;
    public const byte Ping          = 0x05;
    public const byte Pong          = 0x06;
    public const byte SettingsSync  = 0x07;
    public const byte Status        = 0x08;
    public const byte Disconnect    = 0x09;
}

/// <summary>
/// Button identifier constants.
/// </summary>
public static class ButtonId
{
    public const byte Left  = 0x01;
    public const byte Right = 0x02;
}

/// <summary>
/// Button action constants.
/// </summary>
public static class ButtonAction
{
    public const byte Press   = 0x01;
    public const byte Release = 0x02;
}

/// <summary>
/// Connection type byte values for STATUS packets.
/// </summary>
public static class ConnectionTypeByte
{
    public const byte WiFi      = 0x01;
    public const byte Bluetooth = 0x02;
    public const byte Usb       = 0x03;
}
