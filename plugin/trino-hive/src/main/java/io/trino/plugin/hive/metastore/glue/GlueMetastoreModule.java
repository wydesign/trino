/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.hive.metastore.glue;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.ProvidesIntoOptional;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.airlift.units.Duration;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry;
import io.trino.plugin.hive.AllowHiveTableRename;
import io.trino.plugin.hive.HideDeltaLakeTables;
import io.trino.plugin.hive.metastore.HiveMetastoreFactory;
import io.trino.plugin.hive.metastore.RawHiveMetastoreFactory;
import io.trino.plugin.hive.metastore.cache.CachingHiveMetastoreConfig;
import io.trino.spi.NodeManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.GlueClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import java.net.URI;
import java.util.EnumSet;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static com.google.inject.multibindings.ProvidesIntoOptional.Type.DEFAULT;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class GlueMetastoreModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        configBinder(binder).bindConfig(GlueHiveMetastoreConfig.class);

        binder.bind(GlueHiveMetastoreFactory.class).in(Scopes.SINGLETON);
        binder.bind(GlueHiveMetastore.class).in(Scopes.SINGLETON);
        binder.bind(GlueContext.class).in(Scopes.SINGLETON);
        newExporter(binder).export(GlueHiveMetastore.class).withGeneratedName();
        newOptionalBinder(binder, Key.get(HiveMetastoreFactory.class, RawHiveMetastoreFactory.class))
                .setDefault()
                .to(GlueHiveMetastoreFactory.class)
                .in(Scopes.SINGLETON);
        binder.bind(Key.get(boolean.class, AllowHiveTableRename.class)).toInstance(false);
    }

    @ProvidesIntoOptional(DEFAULT)
    @Singleton
    public static Set<GlueHiveMetastore.TableKind> getTableKinds(@HideDeltaLakeTables boolean hideDeltaLakeTables)
    {
        if (hideDeltaLakeTables) {
            return EnumSet.complementOf(EnumSet.of(GlueHiveMetastore.TableKind.DELTA));
        }
        return EnumSet.allOf(GlueHiveMetastore.TableKind.class);
    }

    @Provides
    @Singleton
    public static GlueCache createGlueCache(CachingHiveMetastoreConfig config, NodeManager nodeManager)
    {
        Duration metadataCacheTtl = config.getMetastoreCacheTtl();
        Duration statsCacheTtl = config.getStatsCacheTtl();

        // Disable caching on workers, because there currently is no way to invalidate such a cache.
        // Note: while we could skip CachingHiveMetastoreModule altogether on workers, we retain it so that catalog
        // configuration can remain identical for all nodes, making cluster configuration easier.
        boolean enabled = nodeManager.getCurrentNode().isCoordinator() &&
                (metadataCacheTtl.toMillis() > 0 || statsCacheTtl.toMillis() > 0);

        if (enabled) {
            return new InMemoryGlueCache(metadataCacheTtl, statsCacheTtl, config.getMetastoreCacheMaximumSize());
        }
        return GlueCache.NOOP;
    }

    @Provides
    @Singleton
    public static GlueClient createGlueClient(GlueHiveMetastoreConfig config, OpenTelemetry openTelemetry)
    {
        GlueClientBuilder glue = GlueClient.builder();

        glue.overrideConfiguration(builder -> builder
                .addExecutionInterceptor(AwsSdkTelemetry.builder(openTelemetry)
                        .setCaptureExperimentalSpanAttributes(true)
                        .setRecordIndividualHttpError(true)
                        .build().newExecutionInterceptor())
                .retryPolicy(retry -> retry
                        .numRetries(config.getMaxGlueErrorRetries())));

        if (config.getAwsCredentialsProvider().isPresent()) {
            try {
                Class<?> providerClass = Class.forName(config.getAwsCredentialsProvider().get());
                if (AwsCredentialsProvider.class.isAssignableFrom(providerClass)) {
                    throw new RuntimeException("Invalid credentials provider class: " + providerClass.getName());
                }
                glue.credentialsProvider(providerClass.asSubclass(AwsCredentialsProvider.class)
                        .getConstructor()
                        .newInstance());
            }
            catch (ReflectiveOperationException e) {
                throw new RuntimeException("Error creating an instance of " + config.getAwsCredentialsProvider(), e);
            }
        }
        else if (config.getIamRole().isPresent()) {
            StsClientBuilder sts = StsClient.builder();

            if (config.getAwsAccessKey().isPresent() && config.getAwsSecretKey().isPresent()) {
                sts.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.getAwsAccessKey().get(), config.getAwsSecretKey().get())));
            }

            if (config.getGlueStsEndpointUrl().isPresent() && config.getGlueStsRegion().isPresent()) {
                sts.endpointOverride(URI.create(config.getGlueStsEndpointUrl().get()))
                        .region(Region.of(config.getGlueStsRegion().get()));
            }
            else if (config.getGlueStsRegion().isPresent()) {
                sts.region(Region.of(config.getGlueStsRegion().get()));
            }
            else if (config.getPinGlueClientToCurrentRegion()) {
                sts.region(DefaultAwsRegionProviderChain.builder().build().getRegion());
            }

            glue.credentialsProvider(StsAssumeRoleCredentialsProvider.builder()
                    .refreshRequest(request -> request
                            .roleArn(config.getIamRole().get())
                            .externalId(config.getExternalId().orElse(null)))
                    .stsClient(sts.build())
                    .asyncCredentialUpdateEnabled(true)
                    .build());
        }
        else if (config.getAwsAccessKey().isPresent() && config.getAwsSecretKey().isPresent()) {
            glue.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.getAwsAccessKey().get(), config.getAwsSecretKey().get())));
        }

        ApacheHttpClient.Builder httpClient = ApacheHttpClient.builder()
                .maxConnections(config.getMaxGlueConnections());

        if (config.getGlueEndpointUrl().isPresent()) {
            checkArgument(config.getGlueRegion().isPresent(), "Glue region must be set when Glue endpoint URL is set");
            glue.region(Region.of(config.getGlueRegion().get()));
            httpClient.proxyConfiguration(ProxyConfiguration.builder()
                    .endpoint(URI.create(config.getGlueEndpointUrl().get()))
                    .build());
        }
        else if (config.getGlueRegion().isPresent()) {
            glue.region(Region.of(config.getGlueRegion().get()));
        }
        else if (config.getPinGlueClientToCurrentRegion()) {
            glue.region(DefaultAwsRegionProviderChain.builder().build().getRegion());
        }

        glue.httpClientBuilder(httpClient);

        return glue.build();
    }
}
