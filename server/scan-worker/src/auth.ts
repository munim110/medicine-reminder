import { createRemoteJWKSet, jwtVerify } from "jose";

// Google's public keys for Firebase Auth ID tokens. jose caches them per isolate.
const FIREBASE_KEYS = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"),
);

/**
 * Returns the Firebase uid for a `Bearer <ID token>` header, or throws.
 *
 * Checking issuer and audience is what ties this Worker to one Firebase project: a valid token
 * from any other project is rejected.
 */
export async function verifyFirebaseIdToken(header: string | null, projectId: string): Promise<string> {
  const token = header?.match(/^Bearer (\S+)$/)?.[1];
  if (!token) throw new Error("missing bearer token");

  const { payload } = await jwtVerify(token, FIREBASE_KEYS, {
    issuer: `https://securetoken.google.com/${projectId}`,
    audience: projectId,
    algorithms: ["RS256"],
  });
  if (typeof payload.sub !== "string" || payload.sub.length === 0) throw new Error("token has no subject");
  return payload.sub;
}
