package com.plansync.app;

import android.content.Context;
import android.os.CancellationSignal;
import android.util.Log;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executors;

/**
 * Wraps the Credential Manager API to perform native Google Sign-In.
 * Returns a Google ID token that the WebView passes to Firebase JS SDK via
 * window.onNativeGoogleToken(idToken).
 */
public class GoogleSignInHelper {

    private static final String TAG = "GoogleSignInHelper";

    // This is your Web Client ID from Firebase Console → Authentication →
    // Sign-in method → Google → Web SDK configuration → Web client ID.
    // It looks like: XXXXXXXXXX-xxxxxxxx.apps.googleusercontent.com (client_type 3)
    // NOTE: Use the WEB client ID here, NOT the Android client ID.
    public static final String WEB_CLIENT_ID =
            "599480734973-p5e2dc8pnhl8gu9gsfqlj71al5f9ca8j.apps.googleusercontent.com";

    public interface Callback {
        void onSuccess(String idToken);
        void onFailure(String error);
        void onCancelled();
    }

    public static void signIn(Context context, Callback callback) {
        CredentialManager credentialManager = CredentialManager.create(context);

        GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false) // show all accounts, not just previously used
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)          // show account picker always
                .build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build();

        credentialManager.getCredentialAsync(
                context,
                request,
                new CancellationSignal(),
                Executors.newSingleThreadExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential credential = response.getCredential();
                        if (credential instanceof CustomCredential) {
                            CustomCredential customCred = (CustomCredential) credential;
                            if (GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                    .equals(customCred.getType())) {
                                try {
                                    GoogleIdTokenCredential googleCred =
                                            GoogleIdTokenCredential.createFrom(customCred.getData());
                                    String idToken = googleCred.getIdToken();
                                    if (idToken != null && !idToken.isEmpty()) {
                                        callback.onSuccess(idToken);
                                    } else {
                                        callback.onFailure("Empty ID token received");
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to parse Google credential", e);
                                    callback.onFailure("Failed to parse credential: " + e.getMessage());
                                }
                                return;
                            }
                        }
                        callback.onFailure("Unexpected credential type: " +
                                (credential != null ? credential.getClass().getName() : "null"));
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        // Distinguish user cancellation from real errors
                        if (msg.contains("Cancel") || msg.contains("cancel") ||
                            e.getClass().getSimpleName().contains("Cancel")) {
                            callback.onCancelled();
                        } else {
                            Log.e(TAG, "Credential Manager error: " + msg, e);
                            callback.onFailure(msg);
                        }
                    }
                }
        );
    }
}
