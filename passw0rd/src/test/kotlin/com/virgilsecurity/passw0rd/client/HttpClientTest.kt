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

package com.virgilsecurity.passw0rd.client

import com.virgilsecurity.passw0rd.protobuf.build.Passw0rdProtos
import com.virgilsecurity.passw0rd.utils.PropertyManager
import com.virgilsecurity.passw0rd.data.ProtocolException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * . _  _
 * .| || | _
 * -| || || |   Created by:
 * .| || || |-  Danylo Oliinyk
 * ..\_  || |   on
 * ....|  _/    2019-01-17
 * ...-| | \    at Virgil Security
 * ....|_|-
 */

/**
 * HttpClientTest class.
 */
class HttpClientTest {

    lateinit var httpClient: HttpClientProtobuf

    @BeforeEach fun setup() {
        httpClient = HttpClientProtobuf(PropertyManager.serverAddress)
    }

    @Test fun response_proto_parse() {
        val version = parseVersionAndContent(
                PropertyManager.publicKeyNew,
                PREFIX_PUBLIC_KEY,
                KEY_PUBLIC_KEY
        ).first

        try {
            Passw0rdProtos.EnrollmentRequest.newBuilder().setVersion(version).build().run {
                httpClient.firePost(
                    this,
                    HttpClientProtobuf.AvailableRequests.ENROLL,
                    authToken = WRONG_TOKEN,
                    responseParser = Passw0rdProtos.EnrollmentResponse.parser()
                )
            }
        } catch (t: Throwable) {
            assertTrue(t is ProtocolException)
        }
    }

    /**
     * This function is taken from [ProtocolContext]
     */
    private fun parseVersionAndContent(forParse: String, prefix: String, name: String): Pair<Int, ByteArray> {
        val parsedParts = forParse.split('.')
        if (parsedParts.size != 3)
            throw java.lang.IllegalArgumentException(
                "Provided \'$name\' has wrong parts count. " +
                        "Should be \'3\'. Actual is \'{${parsedParts.size}}\'. "
            )

        if (parsedParts[0] != prefix)
            throw java.lang.IllegalArgumentException(
                "Wrong token prefix. Should be \'$prefix\'. " +
                        "Actual is \'{$parsedParts[0]}\'."
            )

        val version: Int
        try {
            version = parsedParts[1].toInt()
            if (version < 1)
                throw java.lang.IllegalArgumentException("$name version can not be zero or negative number.")
        } catch (e: NumberFormatException) {
            throw java.lang.IllegalArgumentException("$name version can not be parsed.")
        }

        val content: ByteArray
        try {
            content = Base64.getDecoder().decode(parsedParts[2])
        } catch (e: java.lang.IllegalArgumentException) {
            throw java.lang.IllegalArgumentException("$name content can not be parsed.")
        }

        return Pair(version, content)
    }

    companion object {
        private const val PREFIX_PUBLIC_KEY = "PK"
        private const val KEY_PUBLIC_KEY = "Public Key"

        private const val WRONG_TOKEN = "WRONG_TOKEN"
    }
}