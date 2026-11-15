import com.android.apksig.ApkVerifier;

import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/** Verifies v1/v2/v3 signatures and preserves the previous distribution's signing identity. */
public final class VerifyApkSignatures {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: VerifyApkSignatures APK [PREVIOUS_APK]");
        }
        File apk = new File(args[0]);
        if (!apk.isFile()) {
            throw new IllegalStateException("Missing APK: " + apk);
        }
        ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                // Exercise v1 too, even though the app's actual minSdk is 24.
                .setMinCheckedPlatformVersion(23)
                .build().verify();
        System.out.printf("APK signatures: v1=%s v2=%s v3=%s%n",
                result.isVerifiedUsingV1Scheme(), result.isVerifiedUsingV2Scheme(),
                result.isVerifiedUsingV3Scheme());
        if (!result.isVerified() || !result.isVerifiedUsingV1Scheme()
                || !result.isVerifiedUsingV2Scheme() || !result.isVerifiedUsingV3Scheme()) {
            throw new IllegalStateException("APK signature verification failed: "
                    + result.getAllErrors());
        }
        if (result.getSignerCertificates().size() != 1) {
            throw new IllegalStateException("Expected exactly one APK signer");
        }
        X509Certificate certificate = result.getSignerCertificates().get(0);
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        System.out.println("Signer certificate SHA-256: " + sha256);
        if (args.length == 2) {
            File previous = new File(args[1]);
            if (previous.exists()) {
                // Old distributions may have v2 only. Verify their real minSdk.
                ApkVerifier.Result old = new ApkVerifier.Builder(previous).build().verify();
                if (!old.isVerified()) {
                    throw new IllegalStateException("Previous APK does not verify: "
                            + previous + ": " + old.getAllErrors());
                }
                if (!old.getSignerCertificates().equals(result.getSignerCertificates())) {
                    throw new IllegalStateException("Signing certificate changed; refusing to replace "
                            + previous + ". Use the original keystore to preserve in-place updates.");
                }
                System.out.println("Signing certificate matches previous APK: " + previous);
            }
        }
    }
}
