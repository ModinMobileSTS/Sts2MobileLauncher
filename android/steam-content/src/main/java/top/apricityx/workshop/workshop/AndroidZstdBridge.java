package top.apricityx.workshop.workshop;

final class AndroidZstdBridge {
	static {
		System.loadLibrary("workshop_zstd");
	}

	private AndroidZstdBridge() {
	}

	static int decompress(byte[] destination, byte[] compressed) {
		return decompressNative(destination, compressed);
	}

	private static native int decompressNative(byte[] destination, byte[] compressed);
}
