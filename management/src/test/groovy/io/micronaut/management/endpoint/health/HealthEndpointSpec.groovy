/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.management.endpoint.health

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.convert.ArgumentConversionContext
import io.micronaut.core.type.Argument
import io.micronaut.health.HealthStatus
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.management.health.aggregator.DefaultHealthAggregator
import io.micronaut.management.health.indicator.HealthIndicator
import io.micronaut.management.health.indicator.HealthResult
import io.micronaut.management.health.indicator.annotation.Liveness
import io.micronaut.management.health.indicator.annotation.Readiness
import io.micronaut.management.health.indicator.diskspace.DiskSpaceIndicator
import io.micronaut.management.health.indicator.jdbc.JdbcIndicator
import io.micronaut.runtime.server.EmbeddedServer
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import spock.lang.Specification

import javax.sql.DataSource
import java.security.Principal

class HealthEndpointSpec extends Specification {

    void "test the beans are available"() {
        given:
        ApplicationContext context = ApplicationContext.builder("test").build()
        context.registerSingleton(Mock(DataSource))
        context.start()

        expect:
        context.containsBean(HealthEndpoint)
        context.containsBean(DiskSpaceIndicator)
        context.containsBean(DefaultHealthAggregator)
        context.containsBean(JdbcIndicator)

        cleanup:
        context.close()
    }

    void "test the disk space bean can be disabled"() {
        given:
        ApplicationContext context = ApplicationContext.run(['endpoints.health.disk-space.enabled': false])

        expect:
        context.containsBean(HealthEndpoint)
        !context.containsBean(DiskSpaceIndicator)
        context.containsBean(DefaultHealthAggregator)
        !context.containsBean(JdbcIndicator)

        cleanup:
        context.close()
    }

    void "test that jdbc bean can be disabled"() {
        given:
        ApplicationContext context = ApplicationContext.run(['endpoints.health.jdbc.enabled': false])

        expect:
        context.containsBean(HealthEndpoint)
        context.containsBean(DiskSpaceIndicator)
        context.containsBean(DefaultHealthAggregator)
        !context.containsBean(JdbcIndicator)

        cleanup:
        context.close()
    }

    void "test the beans are not available with health disabled"() {
        given:
        ApplicationContext context = ApplicationContext.run(['endpoints.health.enabled': false])

        expect:
        !context.containsBean(HealthEndpoint)
        !context.containsBean(DiskSpaceIndicator)
        !context.containsBean(DefaultHealthAggregator)
        !context.containsBean(JdbcIndicator)

        cleanup:
        context.close()
    }

    void "test the beans are not available with all disabled"() {
        given:
        ApplicationContext context = ApplicationContext.run(['endpoints.all.enabled': false])

        expect:
        !context.containsBean(HealthEndpoint)
        !context.containsBean(DiskSpaceIndicator)
        !context.containsBean(DefaultHealthAggregator)
        !context.containsBean(JdbcIndicator)

        cleanup:
        context.close()
    }

    void "test the beans are available with all disabled and health enabled"() {
        given:
        ApplicationContext context = ApplicationContext.run(['endpoints.all.enabled': false, 'endpoints.health.enabled': true])

        context.start()

        expect:
        context.containsBean(HealthEndpoint)
        context.containsBean(DiskSpaceIndicator)
        context.containsBean(DefaultHealthAggregator)
        !context.containsBean(JdbcIndicator)

        cleanup:
        context.close()
    }

    void "test health endpoint"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'micronaut.application.name': 'foo',
                'endpoints.health.sensitive': false,
                'datasources.one.url': 'jdbc:h2:mem:oneDb;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE',
                'datasources.two.url': 'jdbc:h2:mem:twoDb;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE'
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)

        when:
        def response = rxClient.exchange("/health", Map).blockFirst()
        Map result = response.body()


        then:
        response.code() == HttpStatus.OK.code
        result.status == "UP"
        result.details
        result.details.diskSpace.status == "UP"
        result.details.diskSpace.details.free > 0
        result.details.diskSpace.details.total > 0
        result.details.diskSpace.details.threshold == 1024L * 1024L * 10
        result.details.jdbc.status == "UP"
        result.details.jdbc.details."jdbc:h2:mem:oneDb".status == "UP"
        result.details.jdbc.details."jdbc:h2:mem:oneDb".details.database == "H2"
        result.details.jdbc.details."jdbc:h2:mem:oneDb".details.version == "1.4.200 (2019-10-14)"
        result.details.jdbc.details."jdbc:h2:mem:twoDb".status == "UP"
        result.details.jdbc.details."jdbc:h2:mem:twoDb".details.database == "H2"
        result.details.jdbc.details."jdbc:h2:mem:twoDb".details.version == "1.4.200 (2019-10-14)"
        result.details.service.status == "UP"

        cleanup:
        embeddedServer.close()
    }

    void "test health endpoint with a high diskspace threshold"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'endpoints.health.sensitive': false,
                'endpoints.health.disk-space.threshold': '9999GB'])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)

        when:
        def response = rxClient.exchange("/health", HealthResult)
                                .onErrorResume(throwable -> {
                                    def rsp = ((HttpClientResponseException) throwable).response
                                    rsp.getBody(HealthResult)
                                    return Flux.just(rsp)
        }).blockFirst()
        HealthResult result = response.getBody(HealthResult).get()

        then:
        response.code() == HttpStatus.SERVICE_UNAVAILABLE.code
        result.status == HealthStatus.DOWN
        result.details
        result.details.diskSpace.status == "DOWN"
        result.details.diskSpace.details.error.startsWith("Free disk space below threshold.")

        cleanup:
        embeddedServer.close()
    }

    void "test health endpoint with custom DOWN mapping"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'endpoints.health.sensitive': false,
                'endpoints.health.status.http-mapping.DOWN': 200,
                'endpoints.health.disk-space.threshold': '9999GB'])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)

        when:
        def response = rxClient.exchange("/health", HealthResult)
                                .blockFirst()
        HealthResult result = response.body()

        then:
        response.code() == HttpStatus.OK.code
        result.status == HealthStatus.DOWN
        result.details
        result.details.diskSpace.status == "DOWN"
        result.details.diskSpace.details.error.startsWith("Free disk space below threshold.")

        cleanup:
        embeddedServer.close()
    }

    void "test health endpoint with a non response jdbc datasource"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'endpoints.health.sensitive': false,
                'datasources.one.url': 'jdbc:h2:mem:oneDb;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE',
                'datasources.two.url': 'jdbc:mysql://localhost:59654/foo'
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)

        when:
        def response = rxClient.exchange("/health", Map).onErrorResume(throwable -> {
                def rsp = ((HttpClientResponseException) throwable).response
                rsp.getBody(Map)
                return Flux.just(rsp)
        }).blockFirst()
        Map result = response.getBody(Map).get()

        then:
        response.code() == HttpStatus.SERVICE_UNAVAILABLE.code
        result.status == "DOWN"
        result.details
        result.details.jdbc.status == "DOWN"
        result.details.jdbc.details."jdbc:mysql://localhost:59654/foo".status == "DOWN"
        result.details.jdbc.details."jdbc:mysql://localhost:59654/foo".details.error.startsWith("com.mysql.cj.jdbc.exceptions.CommunicationsException")
        result.details.jdbc.details."jdbc:h2:mem:oneDb".status == "UP"

        cleanup:
        embeddedServer?.close()

    }

    void "test /health/liveness endpoint"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'endpoints.health.sensitive': false,
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)
        embeddedServer.applicationContext.createBean(TestLivenessHealthIndicator.class)

        when:
        def response = rxClient.exchange("/health/liveness", Map).blockFirst()
        Map result = response.body()

        then:
        response.code() == HttpStatus.OK.code
        result.status == "UP"
        result.details
        result.details.liveness.status == "UP"

        cleanup:
        embeddedServer.close()
    }

    void "test /health/readiness endpoint"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.application.name': 'foo',
                'spec.name': getClass().simpleName,
                'endpoints.health.sensitive': false,
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)
        embeddedServer.applicationContext.createBean(TestReadinessHealthIndicator.class)

        when:
        def response = rxClient.exchange("/health/readiness", Map).blockFirst()
        Map result = response.body()

        then:
        response.code() == HttpStatus.OK.code
        result.status == "UP"
        result.details
        result.details.readiness.status == "UP"
        result.details.service.status == "UP"

        cleanup:
        embeddedServer.close()
    }

    void "test /health/readiness endpoint - no app name"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'endpoints.health.sensitive': false,
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)
        embeddedServer.applicationContext.createBean(TestReadinessHealthIndicator.class)

        when:
        def response = rxClient.exchange("/health/readiness", Map).blockFirst()
        Map result = response.body()

        then:
        response.code() == HttpStatus.OK.code
        result.status == "UP"
        result.details
        result.details.readiness.status == "UP"
        result.details.service.status == "UP"

        cleanup:
        embeddedServer.close()
    }

    void "test health/liveness DOWN status means HttpStatus.SERVICE_UNAVAILABLE by default"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'indicator.name': 'TestLivenessDown',
                'endpoints.health.sensitive': false
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)

        when:
        def response = rxClient.exchange("/health/liveness", HealthResult)
                .onErrorResume(throwable -> {
                        def rsp = ((HttpClientResponseException) throwable).response
                        rsp.getBody(HealthResult)
                        return Flux.just(rsp)
                }).blockFirst()
        HealthResult result = response.getBody(HealthResult).get()

        then:
        response.code() == HttpStatus.SERVICE_UNAVAILABLE.code
        result.status == HealthStatus.DOWN

        cleanup:
        embeddedServer.close()
    }

    void "test health/readiness DOWN status means HttpStatus.SERVICE_UNAVAILABLE by default"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'indicator.name': 'TestReadinessDown',
                'endpoints.health.sensitive': false
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)

        when:
        def response = rxClient.exchange("/health/readiness", HealthResult)
                .onErrorResume(throwable -> {
                        def rsp = ((HttpClientResponseException) throwable).response
                        rsp.getBody(HealthResult)
                        return Flux.just(rsp)
                }).blockFirst()
        HealthResult result = response.getBody(HealthResult).get()

        then:
        response.code() == HttpStatus.SERVICE_UNAVAILABLE.code
        result.status == HealthStatus.DOWN

        cleanup:
        embeddedServer.close()
    }

    void "test health/readiness endpoint with custom DOWN mapping"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'indicator.name': 'TestReadinessDown',
                'endpoints.health.sensitive': false,
                'endpoints.health.status.http-mapping.DOWN': 200
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)

        when:
        def response = rxClient.exchange("/health/readiness", HealthResult)
                .blockFirst()
        HealthResult result = response.body()

        then:
        response.code() == HttpStatus.OK.code
        result.status == HealthStatus.DOWN

        cleanup:
        embeddedServer.close()
    }

    void "test health/liveness endpoint with custom DOWN mapping"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': getClass().simpleName,
                'indicator.name': 'TestLivenessDown',
                'endpoints.health.sensitive': false,
                'endpoints.health.status.http-mapping.DOWN': 200
        ])
        URL server = embeddedServer.getURL()
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, server)

        when:
        def response = rxClient.exchange("/health/liveness", HealthResult)
                .blockFirst()
        HealthResult result = response.body()

        then:
        response.code() == HttpStatus.OK.code
        result.status == HealthStatus.DOWN

        cleanup:
        embeddedServer.close()
    }

    @Singleton
    @Requires(property = 'spec.name', value = 'HealthEndpointSpec')
    static class TestPrincipalBinder implements TypedRequestArgumentBinder<Principal> {

        @Override
        Argument<Principal> argumentType() {
            return Argument.of(Principal)
        }

        @Override
        BindingResult<Principal> bind(ArgumentConversionContext<Principal> context, HttpRequest<?> source) {
            return new BindingResult<Principal>() {
                @Override
                Optional<Principal> getValue() {
                    Optional.of(new Principal() {

                        @Override
                        String getName() {
                            return "Test class"
                        }
                    })
                }
            }
        }
    }

    @Singleton
    @Readiness
    static class TestReadinessHealthIndicator implements HealthIndicator {
        @Override
        Publisher<HealthResult> getResult() {
            return Flux.just(HealthResult.builder('readiness').status(HealthStatus.UP).build())
        }
    }

    @Singleton
    @Liveness
    static class TestLivenessHealthIndicator implements HealthIndicator {
        @Override
        Publisher<HealthResult> getResult() {
            return Flux.just(HealthResult.builder('liveness').status(HealthStatus.UP).build())
        }
    }

    @Singleton
    @Liveness
    @Requires(property = 'indicator.name', value = 'TestLivenessDown')
    static class TestLivenessDownHealthIndicator implements HealthIndicator {
        @Override
        Publisher<HealthResult> getResult() {
            return Flux.just(HealthResult.builder('liveness').status(HealthStatus.DOWN).build())
        }
    }
    @Singleton
    @Readiness
    @Requires(property = 'indicator.name', value = 'TestReadinessDown')
    static class TestReadinessDownHealthIndicator implements HealthIndicator {
        @Override
        Publisher<HealthResult> getResult() {
            return Flux.just(HealthResult.builder('readiness').status(HealthStatus.DOWN).build())
        }
    }
}
