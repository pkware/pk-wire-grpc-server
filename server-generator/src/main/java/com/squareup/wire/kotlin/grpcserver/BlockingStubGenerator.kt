/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.kotlin.grpcserver

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.wire.kotlin.grpcserver.StubGenerator.addAbstractStubConstructor
import com.squareup.wire.schema.Rpc
import com.squareup.wire.schema.Service

object BlockingStubGenerator {

    private val clientCalls = ClassName("io.grpc.stub", "ClientCalls")
    private val blockingClientCall = ClassName("io.grpc.stub", "BlockingClientCall")

    internal fun addBlockingStub(
        generator: ClassNameGenerator,
        builder: TypeSpec.Builder,
        service: Service,
        options: KotlinGrpcGenerator.Companion.Options,
    ): TypeSpec.Builder {
        if (!options.suspendingCalls) {
            val serviceClassName = generator.classNameFor(service.type)
            val stubClassName = ClassName(
                packageName = serviceClassName.packageName,
                "${serviceClassName.simpleName}WireGrpc",
                "${serviceClassName.simpleName}BlockingStub",
            )
            return builder
                .addFunction(
                    FunSpec.builder("newBlockingStub")
                        .addParameter("channel", ClassName("io.grpc", "Channel"))
                        .returns(stubClassName)
                        .addCode("return %T(channel)", stubClassName)
                        .build(),
                )
                .addType(
                    TypeSpec.classBuilder(stubClassName)
                        .apply {
                            addAbstractStubConstructor(
                                generator,
                                this,
                                service,
                                ClassName("io.grpc.stub", "AbstractStub"),
                                true,
                            )
                        }
                        .addBlockingStubRpcCalls(generator, service)
                        .build(),
                )
        } else {
            return builder
        }
    }

    private fun TypeSpec.Builder.addBlockingStubRpcCalls(generator: ClassNameGenerator, service: Service): TypeSpec.Builder {
        service.rpcs
            .forEach { rpc ->
                val functions = when {
                    !rpc.requestStreaming && !rpc.responseStreaming -> unary(generator, rpc)
                    rpc.requestStreaming && !rpc.responseStreaming -> clientStreaming(generator, rpc)
                    !rpc.requestStreaming -> serverStreaming(generator, rpc)
                    else -> bidi(generator, rpc)
                }

                functions.forEach(::addFunction)
            }
        return this
    }

    private fun unary(generator: ClassNameGenerator, rpc: Rpc): List<FunSpec> {
        val blockingUnaryCall = MemberName(enclosingClassName = clientCalls, simpleName = "blockingUnaryCall")
        val codeBlock = CodeBlock.of(
            "return %M(channel, get${rpc.name}Method(), callOptions, request)",
            blockingUnaryCall,
        )
        return listOf(
            FunSpec.builder(rpc.name)
                .addParameter("request", ClassName.bestGuess(generator.classNameFor(rpc.requestType!!).toString()))
                .returns(generator.classNameFor(rpc.responseType!!))
                .addCode(codeBlock)
                .build(),
        )
    }

    private fun serverStreaming(generator: ClassNameGenerator, rpc: Rpc): List<FunSpec> {
        val blockingServerStreamingCall = MemberName(enclosingClassName = clientCalls, simpleName = "blockingServerStreamingCall")
        val codeBlock1 = CodeBlock.of(
            "return %M(channel, get${rpc.name}Method(), callOptions, request)",
            blockingServerStreamingCall,
        )
        val v1 = FunSpec.builder(rpc.name)
            .addParameter("request", ClassName.bestGuess(generator.classNameFor(rpc.requestType!!).toString()))
            .returns(Iterator::class.asClassName().parameterizedBy(generator.classNameFor(rpc.responseType!!)))
            .addCode(codeBlock1)
            .build()

        val blockingV2ServerStreamingCall = MemberName(enclosingClassName = clientCalls, simpleName = "blockingV2ServerStreamingCall")
        val codeBlock2 = CodeBlock.of(
            "return %M(channel, get${rpc.name}Method(), callOptions, request)",
            blockingV2ServerStreamingCall,
        )

        // We have to give this method a different name to avoid conflicts.
        // Since this method returns a BlockingClientCall, suffix it with "Call".
        val v2 = FunSpec.builder("${rpc.name}Call")
            .addParameter("request", ClassName.bestGuess(generator.classNameFor(rpc.requestType!!).toString()))
            .returns(
                blockingClientCall.parameterizedBy(
                    ClassName.bestGuess(generator.classNameFor(rpc.requestType!!).toString()),
                    generator.classNameFor(rpc.responseType!!),
                ),
            )
            .addCode(codeBlock2)
            .build()

        return listOf(v1, v2)
    }

    private fun clientStreaming(generator: ClassNameGenerator, rpc: Rpc): List<FunSpec> {
        val blockingClientStreamingCall = MemberName(enclosingClassName = clientCalls, simpleName = "blockingClientStreamingCall")
        val codeBlock = CodeBlock.of(
            "return %M(channel, get${rpc.name}Method(), callOptions)",
            blockingClientStreamingCall,
        )

        return listOf(
            FunSpec.builder(rpc.name)
                .returns(
                    blockingClientCall.parameterizedBy(
                        ClassName.bestGuess(generator.classNameFor(rpc.requestType!!).toString()),
                        generator.classNameFor(rpc.responseType!!),
                    ),
                )
                .addCode(codeBlock)
                .build(),
        )
    }

    private fun bidi(generator: ClassNameGenerator, rpc: Rpc): List<FunSpec> {
        val blockingBidiStreamingCall = MemberName(enclosingClassName = clientCalls, simpleName = "blockingBidiStreamingCall")
        val codeBlock = CodeBlock.of(
            "return %M(channel, get${rpc.name}Method(), callOptions)",
            blockingBidiStreamingCall,
        )

        return listOf(
            FunSpec.builder(rpc.name)
                .returns(
                    blockingClientCall.parameterizedBy(
                        ClassName.bestGuess(generator.classNameFor(rpc.requestType!!).toString()),
                        generator.classNameFor(rpc.responseType!!),
                    ),
                )
                .addCode(codeBlock)
                .build(),
        )
    }
}
