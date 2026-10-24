package fixture;
public final class ProtoCaller {
    public static fixture.proto.Request request() {
        return fixture.proto.Request.newBuilder().setName("native").build();
    }
    public static io.grpc.stub.AbstractBlockingStub<?> stub(io.grpc.Channel channel) {
        return fixture.proto.EchoGrpc.newBlockingStub(channel);
    }
    public static void main(String[] args) {
        if (!request().getName().equals("native")) throw new AssertionError();
    }
}
