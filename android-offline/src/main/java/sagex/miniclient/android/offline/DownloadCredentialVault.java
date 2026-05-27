/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sagex.miniclient.android.offline;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Stores download auth credentials in encrypted preferences.
 *
 * Family buckets are internal routing keys driven by server/job metadata;
 * they are not user-configurable client settings.
 */
public class DownloadCredentialVault {
    private static final Logger log = LoggerFactory.getLogger(DownloadCredentialVault.class);

    private static final String PREF_FILE = "download_auth_vault";
    private static final String KEY_POOL = "pool_json";

    private final SharedPreferences prefs;

    static class AccountSnapshot {
        final String family;
        final String username;
        final String password;
        final int authFailures;
        final int failoverCount;
        final long lastSuccessAt;
        final boolean revoked;

        AccountSnapshot(String family, String username, String password,
                        int authFailures, int failoverCount, long lastSuccessAt, boolean revoked) {
            this.family = family;
            this.username = username;
            this.password = password;
            this.authFailures = authFailures;
            this.failoverCount = failoverCount;
            this.lastSuccessAt = lastSuccessAt;
            this.revoked = revoked;
        }
    }

    DownloadCredentialVault(Context context) {
        this.prefs = createEncryptedPrefs(context.getApplicationContext());
        migrateLegacyIfPresent();
    }

    List<AccountSnapshot> getCandidates(String family, String preferredUsername) {
        JSONObject root = readPool();
        String normalizedFamily = normalizeFamily(family);
        JSONObject famObj = root.optJSONObject(normalizedFamily);
        if (famObj == null) {
            return Collections.emptyList();
        }

        JSONArray arr = famObj.optJSONArray("accounts");
        if (arr == null || arr.length() == 0) {
            return Collections.emptyList();
        }

        final String sticky = famObj.optString("sticky", "");
        List<AccountSnapshot> candidates = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String username = item.optString("username", "");
            String password = item.optString("password", "");
            if (username.isEmpty() || password.isEmpty()) continue;
            candidates.add(new AccountSnapshot(
                    normalizedFamily,
                    username,
                    password,
                    item.optInt("authFailures", 0),
                    item.optInt("failoverCount", 0),
                    item.optLong("lastSuccessAt", 0L),
                    item.optBoolean("revoked", false)));
        }

        if (candidates.isEmpty()) {
            return candidates;
        }

        final String preferred = preferredUsername == null ? "" : preferredUsername.trim();
        candidates.sort(new Comparator<AccountSnapshot>() {
            @Override
            public int compare(AccountSnapshot a, AccountSnapshot b) {
                int aWeight = weight(a, preferred, sticky);
                int bWeight = weight(b, preferred, sticky);
                if (aWeight != bWeight) return Integer.compare(aWeight, bWeight);
                if (a.lastSuccessAt != b.lastSuccessAt) {
                    return Long.compare(b.lastSuccessAt, a.lastSuccessAt);
                }
                return Integer.compare(a.authFailures, b.authFailures);
            }
        });

        return candidates;
    }

    void upsertAccount(String family, String username, String password) {
        if (isBlank(username) || isBlank(password)) return;

        JSONObject root = readPool();
        String normalizedFamily = normalizeFamily(family);
        JSONObject famObj = root.optJSONObject(normalizedFamily);
        if (famObj == null) {
            famObj = new JSONObject();
            putJson(root, normalizedFamily, famObj);
        }
        JSONArray arr = famObj.optJSONArray("accounts");
        if (arr == null) {
            arr = new JSONArray();
            putJson(famObj, "accounts", arr);
        }

        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            if (username.equals(item.optString("username", ""))) {
                putJson(item, "password", password);
                putJson(item, "revoked", false);
                found = true;
                break;
            }
        }
        if (!found) {
            JSONObject item = new JSONObject();
            putJson(item, "username", username);
            putJson(item, "password", password);
            putJson(item, "authFailures", 0);
            putJson(item, "failoverCount", 0);
            putJson(item, "lastSuccessAt", 0L);
            putJson(item, "revoked", false);
            arr.put(item);
        }
        writePool(root);
    }

    void markSuccess(String family, String username) {
        if (isBlank(username)) return;
        JSONObject root = readPool();
        JSONObject famObj = root.optJSONObject(normalizeFamily(family));
        if (famObj == null) return;
        JSONArray arr = famObj.optJSONArray("accounts");
        if (arr == null) return;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            if (username.equals(item.optString("username", ""))) {
                putJson(item, "authFailures", 0);
                putJson(item, "revoked", false);
                putJson(item, "lastSuccessAt", System.currentTimeMillis());
                putJson(famObj, "sticky", username);
                writePool(root);
                return;
            }
        }
    }

    void markAuthFailure(String family, String username, boolean revoked, boolean countedFailover) {
        if (isBlank(username)) return;
        JSONObject root = readPool();
        JSONObject famObj = root.optJSONObject(normalizeFamily(family));
        if (famObj == null) return;
        JSONArray arr = famObj.optJSONArray("accounts");
        if (arr == null) return;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            if (username.equals(item.optString("username", ""))) {
                putJson(item, "authFailures", item.optInt("authFailures", 0) + 1);
                if (countedFailover) {
                    putJson(item, "failoverCount", item.optInt("failoverCount", 0) + 1);
                }
                if (revoked) {
                    putJson(item, "revoked", true);
                }
                writePool(root);
                return;
            }
        }
    }

    private void migrateLegacyIfPresent() {
        // Keep sage/frey compatibility by migrating plain legacy keys once.
        migrateLegacyPair("sage", "download_username", "download_password");
        migrateLegacyPair("sage", "sage_username", "sage_password");
        migrateLegacyPair("frey", "frey_username", "frey_password");
    }

    private void migrateLegacyPair(String family, String userKey, String passKey) {
        if (!prefs.contains(userKey) || !prefs.contains(passKey)) {
            return;
        }
        String user = prefs.getString(userKey, "");
        String pass = prefs.getString(passKey, "");
        if (!isBlank(user) && !isBlank(pass)) {
            upsertAccount(family, user, pass);
            log.info("Migrated legacy download credential keypair for family={}", family);
        }
        prefs.edit().remove(userKey).remove(passKey).apply();
    }

    private JSONObject readPool() {
        String raw = prefs.getString(KEY_POOL, "{}");
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writePool(JSONObject root) {
        prefs.edit().putString(KEY_POOL, root.toString()).apply();
    }

    private static int weight(AccountSnapshot snap, String preferred, String sticky) {
        int w = 10;
        if (snap.revoked) w += 100;
        if (!preferred.isEmpty() && preferred.equals(snap.username)) w -= 8;
        if (!sticky.isEmpty() && sticky.equals(snap.username)) w -= 5;
        w += Math.min(20, snap.authFailures);
        return w;
    }

    private static String normalizeFamily(String family) {
        if (isBlank(family)) return "sage";
        String value = family.trim().toLowerCase();
        if ("sage".equals(value) || "frey".equals(value)) return value;
        return value;
    }

    private static SharedPreferences createEncryptedPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREF_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize secure credential vault", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void putJson(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
        }
    }
}
