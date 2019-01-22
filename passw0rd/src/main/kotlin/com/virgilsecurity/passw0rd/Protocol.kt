/*
 * Copyright (c) 2015-2018, Virgil Security, Inc.
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

package com.virgilsecurity.passw0rd

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.virgilsecurity.passw0rd.client.HttpClientProtobuf
import com.virgilsecurity.passw0rd.data.*
import com.virgilsecurity.passw0rd.protobuf.build.Passw0rdProtos
import com.virgilsecurity.passw0rd.utils.EnrollResult
import com.virgilsecurity.passw0rd.utils.Utils
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import virgil.crypto.phe.PheCipher
import virgil.crypto.phe.PheClient
import virgil.crypto.phe.PheClientEnrollAccountResult
import virgil.crypto.phe.PheException

/**
 * . _  _
 * .| || | _
 * -| || || |   Created by:
 * .| || || |-  Danylo Oliinyk
 * ..\_  || |   on
 * ....|  _/    12/14/18
 * ...-| | \    at Virgil Security
 * ....|_|-
 */

/**
 * Protocol class implements passw0rd client-server protocol.
 */
class Protocol(protocolContext: ProtocolContext, private val httpClient: HttpClientProtobuf = HttpClientProtobuf()) {

    private val appToken: String = protocolContext.appToken
    private val pheClients: Map<Int, PheClient> = protocolContext.pheClients
    private val currentVersion: Int = protocolContext.version
    private val updateToken: Passw0rdProtos.VersionedUpdateToken? = protocolContext.updateToken
    private val pheCipher: PheCipher by lazy { PheCipher().apply { setupDefaults() } }

    /**
     * This function requests pseudo-random data from server and uses it to protect [password] and data encryption key.
     *
     * @throws IllegalArgumentException
     * @throws ProtocolException
     * @throws PheException
     */
    fun enrollAccount(password: String): Deferred<EnrollResult> = GlobalScope.async {
        if (password.isBlank()) Utils.shouldNotBeEmpty("password")

        Passw0rdProtos.EnrollmentRequest.newBuilder().setVersion(currentVersion).build().run {
            httpClient.firePost(
                    this,
                    HttpClientProtobuf.AvailableRequests.ENROLL,
                    authToken = appToken,
                    responseParser = Passw0rdProtos.EnrollmentResponse.parser()
            ).let { response ->
                val enrollResult = try {
                    pheClients[response.version]!!.enrollAccount(response.response.toByteArray(),
                                                                 password.toByteArray())
                } catch (exception: PheException) {
                    throw InvalidProofException()
                }

                val enrollmentRecord = Passw0rdProtos.DatabaseRecord
                        .newBuilder()
                        .setVersion(currentVersion)
                        .setRecord(ByteString.copyFrom(enrollResult.enrollmentRecord))
                        .build()
                        .toByteArray()

                EnrollResult(enrollmentRecord, enrollResult.accountKey)
            }
        }
    }

    /**
     * This function verifies a [password] against [enrollmentRecord] using passw0rd service.
     *
     * @throws IllegalArgumentException
     * @throws ProtocolException
     * @throws PheException
     * @throws InvalidPasswordException
     * @throws InvalidProtobufTypeException
     */
    fun verifyPassword(password: String, enrollmentRecord: ByteArray): Deferred<ByteArray> = GlobalScope.async {
        if (password.isBlank()) Utils.shouldNotBeEmpty("password")
        if (enrollmentRecord.isEmpty()) Utils.shouldNotBeEmpty("enrollmentRecord")

        val (version, record) = try {
            Passw0rdProtos.DatabaseRecord.parseFrom(enrollmentRecord).let {
                it.version to it.record.toByteArray()
            }
        } catch (e: InvalidProtocolBufferException) {
            throw InvalidProtobufTypeException()
        }

        if (pheClients[version] == null)
            throw NoKeysFoundException("Unable to find keys corresponding to record's version $version.")

        val request = pheClients[version]!!.createVerifyPasswordRequest(password.toByteArray(), record)

        val verifyPasswordRequest = Passw0rdProtos.VerifyPasswordRequest
                .newBuilder()
                .setVersion(version)
                .setRequest(ByteString.copyFrom(request))
                .build()

        httpClient.firePost(
                verifyPasswordRequest,
                HttpClientProtobuf.AvailableRequests.VERIFY_PASSWORD,
                authToken = appToken,
                responseParser = Passw0rdProtos.VerifyPasswordResponse.parser()
        ).let {
            val key = try {
                pheClients[version]!!.checkResponseAndDecrypt(password.toByteArray(),
                                                              record,
                                                              it.response.toByteArray())
            } catch (exception: PheException) {
                throw InvalidProofException()
            }

            if (key.isEmpty())
                throw InvalidPasswordException("The password you specified is wrong.")

            key
        }
    }

    /**
     * This function increments record version and updates [oldRecord] with provided [updateToken] from
     * [ProtocolContext] and returns updated record.
     *
     * @throws IllegalArgumentException
     * @throws PheException
     * @throws InvalidProtobufTypeException
     */
    fun updateEnrollmentRecord(oldRecord: ByteArray): Deferred<ByteArray> = GlobalScope.async {
        if (oldRecord.isEmpty()) Utils.shouldNotBeEmpty("oldRecord")
        if (updateToken == null) Utils.shouldNotBeEmpty("update token")

        val (recordVersion, record) = try {
            Passw0rdProtos.DatabaseRecord.parseFrom(oldRecord).let {
                it.version to it.record.toByteArray()
            }
        } catch (e: InvalidProtocolBufferException) {
            throw InvalidProtobufTypeException()
        }

        if ((recordVersion + 1) == updateToken.version) {
            val newRecord =
                    pheClients[updateToken.version]!!.updateEnrollmentRecord(record,
                                                                             updateToken.updateToken.toByteArray())

            Passw0rdProtos.DatabaseRecord.newBuilder()
                    .setRecord(ByteString.copyFrom(newRecord))
                    .setVersion(updateToken.version).build()
                    .toByteArray()
        } else {
            throw IllegalArgumentException(
                    "Update Token version must be greater by 1 than current. " +
                            "Token version is ${updateToken.version}. " +
                            "Current version is $currentVersion."
            )
        }
    }

    /**
     * This function encrypts provided [data] using [accountKey].
     *
     * @throws IllegalArgumentException
     * @throws PheException
     */
    fun encrypt(data: ByteArray, accountKey: ByteArray): ByteArray {
        if (data.isEmpty()) Utils.shouldNotBeEmpty("data")
        if (accountKey.isEmpty()) Utils.shouldNotBeEmpty("accountKey")

        return pheCipher.encrypt(data, accountKey)
    }

    /**
     * This function decrypts provided [data] using [accountKey].
     *
     * @throws IllegalArgumentException
     * @throws PheException
     */
    fun decrypt(data: ByteArray, accountKey: ByteArray): ByteArray {
        if (data.isEmpty()) Utils.shouldNotBeEmpty("data")
        if (accountKey.isEmpty()) Utils.shouldNotBeEmpty("accountKey")

        return pheCipher.decrypt(data, accountKey)
    }
}