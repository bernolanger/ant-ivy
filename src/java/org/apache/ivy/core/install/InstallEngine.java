/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivy.core.install;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;

import org.apache.ivy.core.cache.CacheManager;
import org.apache.ivy.core.module.descriptor.Configuration;
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleId;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.publish.PublishEngine;
import org.apache.ivy.core.publish.PublishOptions;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolveEngine;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.search.SearchEngine;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.conflict.NoConflictManager;
import org.apache.ivy.plugins.matcher.ExactPatternMatcher;
import org.apache.ivy.plugins.matcher.MatcherHelper;
import org.apache.ivy.plugins.matcher.PatternMatcher;
import org.apache.ivy.plugins.resolver.DependencyResolver;
import org.apache.ivy.util.Message;
import org.apache.ivy.util.filter.Filter;
import org.apache.ivy.util.filter.FilterHelper;

public class InstallEngine {
	private IvySettings _settings;
	private ResolveEngine _resolveEngine;
	private PublishEngine _publishEngine;
	private SearchEngine _searchEngine;
	
	public InstallEngine(IvySettings settings, SearchEngine searchEngine, ResolveEngine resolveEngine, PublishEngine publishEngine) {
		_settings = settings;
		_searchEngine = searchEngine;
		_resolveEngine = resolveEngine;
		_publishEngine = publishEngine;
	}

	public ResolveReport install(ModuleRevisionId mrid, String from, String to, boolean transitive, boolean validate, boolean overwrite, Filter artifactFilter, File cache, String matcherName) throws IOException {
        if (cache == null) {
            cache = _settings.getDefaultCache();
        }
        if (artifactFilter == null) {
            artifactFilter = FilterHelper.NO_FILTER;
        }
        DependencyResolver fromResolver = _settings.getResolver(from);
        DependencyResolver toResolver = _settings.getResolver(to);
        if (fromResolver == null) {
            throw new IllegalArgumentException("unknown resolver "+from+". Available resolvers are: "+_settings.getResolverNames());
        }
        if (toResolver == null) {
            throw new IllegalArgumentException("unknown resolver "+to+". Available resolvers are: "+_settings.getResolverNames());
        }
        PatternMatcher matcher = _settings.getMatcher(matcherName);
        if (matcher == null) {
            throw new IllegalArgumentException("unknown matcher "+matcherName+". Available matchers are: "+_settings.getMatcherNames());
        }
        
        // build module file declaring the dependency
        Message.info(":: installing "+mrid+" ::");
        DependencyResolver oldDicator = _resolveEngine.getDictatorResolver();
        boolean log = _settings.logNotConvertedExclusionRule();
        try {
        	_settings.setLogNotConvertedExclusionRule(true);
        	_resolveEngine.setDictatorResolver(fromResolver);
            
            DefaultModuleDescriptor md = new DefaultModuleDescriptor(ModuleRevisionId.newInstance("apache", "ivy-install", "1.0"), _settings.getStatusManager().getDefaultStatus(), new Date());
            md.addConfiguration(new Configuration("default"));
            md.addConflictManager(new ModuleId(ExactPatternMatcher.ANY_EXPRESSION, ExactPatternMatcher.ANY_EXPRESSION), ExactPatternMatcher.INSTANCE, new NoConflictManager());
            
            if (MatcherHelper.isExact(matcher, mrid)) {
                DefaultDependencyDescriptor dd = new DefaultDependencyDescriptor(md, mrid, false, false, transitive);
                dd.addDependencyConfiguration("default", "*");
                md.addDependency(dd);
            } else {
                Collection mrids = _searchEngine.findModuleRevisionIds(fromResolver, mrid, matcher); 
                                
                for (Iterator iter = mrids.iterator(); iter.hasNext();) {
                    ModuleRevisionId foundMrid = (ModuleRevisionId)iter.next();
                    Message.info("\tfound "+foundMrid+" to install: adding to the list");
                    DefaultDependencyDescriptor dd = new DefaultDependencyDescriptor(md, foundMrid, false, false, transitive);
                    dd.addDependencyConfiguration("default", "*");
                    md.addDependency(dd);
                }
            }                       
            
            // resolve using appropriate resolver
            ResolveReport report = new ResolveReport(md);
            
            Message.info(":: resolving dependencies ::");
            IvyNode[] dependencies = _resolveEngine.getDependencies(
            		md, 
            		new ResolveOptions()
            			.setConfs(new String[] {"default"})
            			.setCache(CacheManager.getInstance(_settings, cache)), 
            		report);
            report.setDependencies(Arrays.asList(dependencies), artifactFilter);
            
            Message.info(":: downloading artifacts to cache ::");
            _resolveEngine.downloadArtifacts(report, getCacheManager(cache), false, artifactFilter);

            // now that everything is in cache, we can publish all these modules
            Message.info(":: installing in "+to+" ::");
            for (int i = 0; i < dependencies.length; i++) {
                ModuleDescriptor depmd = dependencies[i].getDescriptor();
                if (depmd != null) {
                    Message.verbose("installing "+depmd.getModuleRevisionId());
                    _publishEngine.publish(
                    		depmd, 
                            Collections.singleton(cache.getAbsolutePath()+"/"+_settings.getCacheArtifactPattern()),
                            toResolver, 
                            new PublishOptions()
	                    		.setSrcIvyPattern(cache.getAbsolutePath()+"/"+_settings.getCacheIvyPattern())
	                    		.setOverwrite(overwrite));
                }
            }

            Message.info(":: install resolution report ::");
            
            // output report
            report.output(_settings.getReportOutputters(), cache);

            return report;
        } finally {
        	_resolveEngine.setDictatorResolver(oldDicator);
            _settings.setLogNotConvertedExclusionRule(log);
        }
    }
	
	private CacheManager getCacheManager(File cache) {
		//TODO : reuse instance
		CacheManager cacheManager = new CacheManager(_settings, cache);
		return cacheManager;
	}


}
