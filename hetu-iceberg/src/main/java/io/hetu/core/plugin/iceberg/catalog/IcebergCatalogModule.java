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
package io.hetu.core.plugin.iceberg.catalog;

import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Scopes;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.hetu.core.plugin.iceberg.CatalogType;
import io.hetu.core.plugin.iceberg.IcebergConfig;
import io.hetu.core.plugin.iceberg.catalog.file.FileMetastoreTableOperationsProvider;
import io.hetu.core.plugin.iceberg.catalog.file.IcebergFileMetastoreCatalogModule;
import io.hetu.core.plugin.iceberg.catalog.glue.IcebergGlueCatalogModule;
import io.hetu.core.plugin.iceberg.catalog.hms.IcebergHiveMetastoreCatalogModule;
import io.hetu.core.plugin.iceberg.catalog.hms.TrinoHiveCatalogFactory;
import io.prestosql.plugin.hive.metastore.DecoratedHiveMetastoreModule;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.plugin.hive.metastore.HiveMetastoreFactory;
import io.prestosql.plugin.hive.metastore.RawHiveMetastoreFactory;
import io.prestosql.plugin.hive.metastore.cache.SharedHiveMetastoreCache;

import java.util.Optional;

import static io.airlift.configuration.ConditionalModule.installModuleIf;
import static io.hetu.core.plugin.iceberg.CatalogType.GLUE;
import static io.hetu.core.plugin.iceberg.CatalogType.HIVE_METASTORE;
import static io.hetu.core.plugin.iceberg.CatalogType.TESTING_FILE_METASTORE;
import static java.util.Objects.requireNonNull;

public class IcebergCatalogModule
        extends AbstractConfigurationAwareModule
{
    private final Optional<HiveMetastore> metastore;

    public IcebergCatalogModule(Optional<HiveMetastore> metastore)
    {
        this.metastore = requireNonNull(metastore, "metastore is null");
    }

    @Override
    protected void setup(Binder binder)
    {
        if (metastore.isPresent()) {
            binder.bind(HiveMetastoreFactory.class).annotatedWith(RawHiveMetastoreFactory.class).toInstance(HiveMetastoreFactory.ofInstance(metastore.get()));
            binder.bind(MetastoreValidator.class).asEagerSingleton();
            install(new DecoratedHiveMetastoreModule());
            binder.bind(IcebergTableOperationsProvider.class).to(FileMetastoreTableOperationsProvider.class).in(Scopes.SINGLETON);
            binder.bind(TrinoCatalogFactory.class).to(TrinoHiveCatalogFactory.class).in(Scopes.SINGLETON);
        }
        else {
            bindCatalogModule(HIVE_METASTORE, new IcebergHiveMetastoreCatalogModule());
            bindCatalogModule(TESTING_FILE_METASTORE, new IcebergFileMetastoreCatalogModule());
            bindCatalogModule(GLUE, new IcebergGlueCatalogModule());
        }
    }

    public static class MetastoreValidator
    {
        @Inject
        public MetastoreValidator(SharedHiveMetastoreCache metastoreCache)
        {
            if (metastoreCache.isEnabled()) {
                throw new RuntimeException("Hive metastore caching must not be enabled for Iceberg");
            }
        }
    }

    private void bindCatalogModule(CatalogType catalogType, Module module)
    {
        install(installModuleIf(
                IcebergConfig.class,
                config -> config.getCatalogType() == catalogType,
                module));
    }
}
