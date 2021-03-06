/*
 * Copyright (c) 2015-2020, Virgil Security, Inc.
 *
 * Lead Maintainer: Virgil Security Inc. <support@virgilsecurity.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     (1) Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *
 *     (2) Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *     (3) Neither the name of virgil nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.virgilsecurity.purekit;

import com.virgilsecurity.crypto.foundation.Base64;
import com.virgilsecurity.purekit.client.HttpKmsClient;
import com.virgilsecurity.purekit.client.HttpPheClient;
import com.virgilsecurity.purekit.client.HttpPureClient;
import com.virgilsecurity.purekit.exception.PureCryptoException;
import com.virgilsecurity.purekit.exception.PureException;
import com.virgilsecurity.purekit.exception.PureLogicException;
import com.virgilsecurity.purekit.storage.PureModelSerializer;
import com.virgilsecurity.purekit.storage.PureModelSerializerDependent;
import com.virgilsecurity.purekit.storage.PureStorage;
import com.virgilsecurity.purekit.storage.virgil.VirgilCloudPureStorage;
import com.virgilsecurity.purekit.utils.ValidationUtils;
import com.virgilsecurity.sdk.crypto.VirgilCrypto;
import com.virgilsecurity.sdk.crypto.VirgilPublicKey;
import com.virgilsecurity.sdk.crypto.exceptions.CryptoException;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PureContext class represents dependencies needed to initialize Pure.
 */
public class PureContext {

    static class Credentials {
        private byte[] payload1;
        private byte[] payload2;
        private byte[] payload3;
        private int version;

        private Credentials(byte[] payload1, byte[] payload2, byte[] payload3, int version) {
            this.payload1 = payload1;
            this.payload2 = payload2;
            this.payload3 = payload3;
            this.version = version;
        }

        byte[] getPayload1() {
            return payload1;
        }

        byte[] getPayload2() {
            return payload2;
        }

        byte[] getPayload3() {
            return payload3;
        }

        int getVersion() {
            return version;
        }
    }

    private static final String NMS_PREFIX = "NM";
    private static final String BUPPK_PREFIX = "BU";
    private static final String SECRET_KEY_PREFIX = "SK";
    private static final String PUBLIC_KEY_PREFIX = "PK";

    private final VirgilCrypto crypto;
    private final VirgilPublicKey buppk;
    private final Credentials secretKey;
    private final Credentials publicKey;
    private final NonrotatableSecrets nonrotatableSecrets;
    private PureStorage storage;
    private final HttpPheClient pheClient;
    private final HttpKmsClient kmsClient;
    private final Map<String, List<VirgilPublicKey>> externalPublicKeys;
    private Credentials updateToken;

    private PureContext(VirgilCrypto crypto,
                        String appToken,
                        String nms,
                        String buppk,
                        String secretKey,
                        String publicKey,
                        PureStorage storage,
                        Map<String, List<String>> externalPublicKeys,
                        String pheServiceAddress,
                        String kmsServiceAddress) throws PureException, MalformedURLException {
        ValidationUtils.checkNull(crypto, "crypto");
        ValidationUtils.checkNullOrEmpty(appToken, "appToken");
        ValidationUtils.checkNullOrEmpty(nms, "nms");
        ValidationUtils.checkNullOrEmpty(buppk, "buppk");
        ValidationUtils.checkNullOrEmpty(secretKey, "secretKey");
        ValidationUtils.checkNullOrEmpty(publicKey, "publicKey");
        ValidationUtils.checkNull(storage, "storage");
        ValidationUtils.checkNullOrEmpty(pheServiceAddress, "pheServiceAddress");
        ValidationUtils.checkNullOrEmpty(kmsServiceAddress, "kmsServiceAddress");

        this.crypto = crypto;

        Credentials nmsCred = PureContext.parseCredentials(NMS_PREFIX, nms, false, 1);
        this.nonrotatableSecrets = NonrotatableSecretsGenerator.generateSecrets(nmsCred.getPayload1());

        byte[] buppkData = PureContext.parseCredentials(BUPPK_PREFIX, buppk, false, 1).getPayload1();
        try {
            this.buppk = crypto.importPublicKey(buppkData);
        } catch (CryptoException e) {
            throw new PureCryptoException(e);
        }

        this.secretKey = PureContext.parseCredentials(SECRET_KEY_PREFIX, secretKey, true, 3);
        this.publicKey = PureContext.parseCredentials(PUBLIC_KEY_PREFIX, publicKey, true, 2);
        this.pheClient = new HttpPheClient(appToken, new URL(pheServiceAddress));
        this.kmsClient = new HttpKmsClient(appToken, new URL(kmsServiceAddress));

        if (storage instanceof PureModelSerializerDependent) {
            PureModelSerializerDependent dependent = (PureModelSerializerDependent)storage;

            PureModelSerializer serializer = new PureModelSerializer(crypto, this.nonrotatableSecrets.getVskp());
            dependent.setPureModelSerializer(serializer);
        }

        this.storage = storage;

        if (externalPublicKeys != null) {
            this.externalPublicKeys = new HashMap<>(externalPublicKeys.size());
            for (String key : externalPublicKeys.keySet()) {
                List<String> publicKeysBase64 = externalPublicKeys.get(key);
                ArrayList<VirgilPublicKey> publicKeys = new ArrayList<>(publicKeysBase64.size());

                for (String publicKeyBase64 : publicKeysBase64) {
                    VirgilPublicKey pubKey;
                    try {
                        pubKey = crypto.importPublicKey(Base64.decode(publicKeyBase64.getBytes()));
                    } catch (CryptoException e) {
                        throw new PureCryptoException(e);
                    }
                    publicKeys.add(pubKey);
                }

                this.externalPublicKeys.put(key, publicKeys);
            }
        } else {
            this.externalPublicKeys = new HashMap<>();
        }

        if (this.secretKey.getVersion() != this.publicKey.getVersion()) {
            throw new PureLogicException(PureLogicException.ErrorStatus.KEYS_VERSION_MISMATCH);
        }
    }

    /**
     * Designed for usage with Virgil Cloud storage.
     *
     * @param at Application token. AT.****
     * @param nm Nonrotatable master secret. NM.****
     * @param bu Backup public key. BU.****
     * @param sk App secret key. SK.****
     * @param pk Service public key. PK.****
     * @param externalPublicKeys External public keys that will be added during encryption by
     *                           default. Map key is dataId, value is list of base64 public keys.
     */
    public static PureContext createContext(String at,
                                            String nm,
                                            String bu,
                                            String sk,
                                            String pk,
                                            Map<String, List<String>> externalPublicKeys) throws PureException, MalformedURLException {

        return PureContext.createContext(
            at, nm, bu, sk, pk,
            externalPublicKeys,
            HttpPheClient.SERVICE_ADDRESS,
            HttpPureClient.SERVICE_ADDRESS,
            HttpKmsClient.SERVICE_ADDRESS
        );
    }

    /**
     * Designed for usage with Virgil Cloud storage.
     *
     * @param at Application token. AT.****
     * @param nm Nonrotatable master secret. NM.****
     * @param bu Backup public key. BU.****
     * @param sk App secret key. SK.****
     * @param pk Service public key. PK.****
     * @param externalPublicKeys External public keys that will be added during encryption by
     *                           default. Map key is dataId, value is list of base64 public keys.
     * @param pheServiceAddress PHE service address.
     * @param pureServiceAddress Pure service address.
     */
    public static PureContext createContext(String at,
                                            String nm,
                                            String bu,
                                            String sk,
                                            String pk,
                                            Map<String, List<String>> externalPublicKeys,
                                            String pheServiceAddress,
                                            String pureServiceAddress,
                                            String kmsServiceAddress) throws PureException, MalformedURLException {

        ValidationUtils.checkNullOrEmpty(at, "at");
        ValidationUtils.checkNullOrEmpty(pureServiceAddress, "pureServiceAddress");

        VirgilCrypto crypto = new VirgilCrypto();
        HttpPureClient pureClient = new HttpPureClient(at, new URL(pureServiceAddress));

        VirgilCloudPureStorage storage = new VirgilCloudPureStorage(pureClient);

        return new PureContext(
            crypto,
            at, nm, bu, sk, pk,
            storage,
            externalPublicKeys,
            pheServiceAddress,
            kmsServiceAddress
        );
    }

    /**
     * Designed for usage with custom PureStorage.
     *
     * @param at Application token. AT.****
     * @param nm Nonrotatable master secret. NM.****
     * @param bu Backup public key. BU.****
     * @param sk App secret key. SK.****
     * @param pk Service public key. PK.****
     * @param storage PureStorage.
     * @param externalPublicKeys External public keys that will be added during encryption by
     *                           default. Map key is dataId, value is list of base64 public keys.
     */
    public static PureContext createContext(String at,
                                            String nm,
                                            String bu,
                                            String sk,
                                            String pk,
                                            PureStorage storage,
                                            Map<String, List<String>> externalPublicKeys) throws PureException, MalformedURLException {

        return PureContext.createContext(
            at, nm, bu, sk, pk, storage,
            externalPublicKeys,
            HttpPheClient.SERVICE_ADDRESS,
            HttpKmsClient.SERVICE_ADDRESS
        );
    }

    /**
     * Designed for usage with custom PureStorage.
     *
     * @param at Application token. AT.****
     * @param nm Nonrotatable master secret. NM.****
     * @param bu Backup public key. BU.****
     * @param sk App secret key. SK.****
     * @param pk Service public key. PK.****
     * @param storage PureStorage.
     * @param externalPublicKeys External public keys that will be added during encryption by
     *                           default. Map key is dataId, value is list of base64 public keys.
     * @param pheServiceAddress PHE service address.
     */
    public static PureContext createContext(String at,
                                            String nm,
                                            String bu,
                                            String sk,
                                            String pk,
                                            PureStorage storage,
                                            Map<String, List<String>> externalPublicKeys,
                                            String pheServiceAddress,
                                            String kmsServiceAddress) throws PureException, MalformedURLException {

        return new PureContext(
            new VirgilCrypto(),
            at, nm, bu, sk, pk,
            storage,
            externalPublicKeys,
            pheServiceAddress,
            kmsServiceAddress
        );
    }

    private static Credentials parseCredentials(String prefix,
                                                String credentials,
                                                boolean isVersioned,
                                                int numberOfPayloads) throws PureException {
        ValidationUtils.checkNullOrEmpty(prefix, "prefix");
        ValidationUtils.checkNullOrEmpty(credentials, "credentials");

        String[] parts = credentials.split("\\.");

        int numberOfParts = 1 + numberOfPayloads + (isVersioned ? 1 : 0);

        if (parts.length != numberOfParts) {
            throw new PureLogicException(PureLogicException.ErrorStatus.CREDENTIALS_PARSING_ERROR);
        }

        int index = 0;

        if (!parts[index].equals(prefix)) {
            throw new PureLogicException(PureLogicException.ErrorStatus.CREDENTIALS_PARSING_ERROR);
        }
        index++;

        int version;
        if (isVersioned) {
            version = Integer.parseInt(parts[index]);
            index++;
        } else {
            version = 0;
        }


        byte[] payload1;
        byte[] payload2 = null;
        byte[] payload3 = null;

        payload1 = Base64.decode(parts[index].getBytes());
        numberOfPayloads--;
        index++;

        if (numberOfPayloads > 0) {
            payload2 = Base64.decode(parts[index].getBytes());
            numberOfPayloads--;
            index++;
        }

        if (numberOfPayloads > 0) {
            payload3 = Base64.decode(parts[index].getBytes());
        }

        return new Credentials(payload1, payload2, payload3, version);
    }

    /**
     * Returns PureStorage.
     *
     * @return PureStorage.
     */
    public PureStorage getStorage() {
        return storage;
    }

    /**
     * Returns PureStorage.
     *
     * @return PureStorage.
     */
    public Credentials getUpdateToken() {
        return updateToken;
    }

    /**
     * Sets Update token.
     */
    public void setUpdateToken(String updateToken) throws PureException {
        this.updateToken = PureContext.parseCredentials("UT", updateToken, true, 3);

        if (this.updateToken.getVersion() != this.publicKey.getVersion() + 1) {
            throw new PureLogicException(PureLogicException.ErrorStatus.UPDATE_TOKEN_VERSION_MISMATCH);
        }
    }

    public void setStorage(PureStorage storage) {
        this.storage = storage;
    }

    /**
     * Returns backup public key.
     *
     * @return Backup public key.
     */
    public VirgilPublicKey getBuppk() {
        return buppk;
    }

    /**
     * Returns app secret key.
     *
     * @return App secret key.
     */
    public Credentials getSecretKey() {
        return secretKey;
    }

    /**
     * Returns service public key.
     *
     * @return Service public key.
     */
    public Credentials getPublicKey() {
        return publicKey;
    }

    /**
     * Returns phe client.
     *
     * @return PHE client.
     */
    public HttpPheClient getPheClient() {
        return pheClient;
    }

    /**
     * Returns external public keys.
     *
     * @return external public keys.
     */
    public Map<String, List<VirgilPublicKey>> getExternalPublicKeys() {
        return externalPublicKeys;
    }

    /**
     * Returns crypto.
     *
     * @return VirgilCrypto.
     */
    public VirgilCrypto getCrypto() {
        return crypto;
    }

    public HttpKmsClient getKmsClient() {
        return kmsClient;
    }

    public NonrotatableSecrets getNonrotatableSecrets() {
        return nonrotatableSecrets;
    }
}
