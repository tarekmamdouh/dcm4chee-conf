package org.dcm4chee.conf.storage;

import org.dcm4che3.conf.core.DelegatingConfiguration;
import org.dcm4che3.conf.core.Nodes;
import org.dcm4che3.conf.core.api.Configuration;
import org.dcm4che3.conf.core.api.ConfigurationException;
import org.dcm4che3.conf.core.api.Path;
import org.dcm4che3.conf.core.util.SplittedPath;
import org.dcm4chee.cache.Cache;
import org.dcm4chee.cache.CacheByName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

/**
 * To workaround the limitation of infinispan (at least 5.x) we maintain the keyset inside a special entry {@link InfinispanCachingConfigurationDecorator#KEYSET_KEY}
 */
@SuppressWarnings("unchecked")
@ApplicationScoped
public class InfinispanCachingConfigurationDecorator extends DelegatingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DelegatingConfiguration.class);

    private static final int level = 3;
    private static final String KEYSET_KEY = "#keySet";


    @Inject
    @CacheByName("configuration")
    private Cache<String, Map<String, Object>> cache;

    public InfinispanCachingConfigurationDecorator() {
    }

    public void setDelegate(Configuration delegate) {
        this.delegate = delegate;
    }

    @Override
    public void refreshNode(Path path) throws ConfigurationException {

        lock();
        super.refreshNode(path);

        // reload fully
        Map<String, Object> root = delegate.getConfigurationRoot();
        clearCache();
        persistTopLayerToCache(root, new ArrayList<>());

    }


    private void persistTopLayerToCache(Map<String, Object> m, List<Object> pathItems) {

        if (pathItems.size() == level) {
            putIntoCache(Nodes.toSimpleEscapedPath(pathItems), m);
        } else if (pathItems.size() < level) {
            m.entrySet().forEach((entry) -> {
                pathItems.add(entry.getKey());

                try {
                    persistTopLayerToCache((Map<String, Object>) entry.getValue(), pathItems);
                } catch (ClassCastException e) {
                    // this should not happen, but after all ignore and let pass through
                    log.error("Unexpected node above serialization level: " + entry.getValue());
                }

                pathItems.remove(pathItems.size() - 1);
            });
        } else throw new IllegalArgumentException("PathItems size is greater than level (" + level + "):" + pathItems);

    }

    private Map<String, Object> getWrappedRoot() {

        HashMap<String, Object> root = new HashMap<>();

        // TODO: we need to switch to some proper API call once we move to a newer infinispan
        // for now it's not so critical since most conf calls will go directly to certain entries

        for (String path : getCacheKeySet()) {
            Nodes.replaceNode(root, cache.get(path), Path.fromSimpleEscapedPath(path).getPathItems());
        }

        return root;
    }


    @Override
    public Map<String, Object> getConfigurationRoot() throws ConfigurationException {
        return (Map<String, Object>) Nodes.deepCloneNode(getWrappedRoot());
    }

    @Override
    public Object getConfigurationNode(Path path, Class configurableClass) throws ConfigurationException {
        return Nodes.deepCloneNode(getConfigurationNodeFromCache(path));
    }

    private Object getConfigurationNodeFromCache(Path path) {

        SplittedPath splittedPath = getSplittedPath(path);
        if (splittedPath==null) return null;

        // fallback if requested one of top levels
        if (splittedPath.getOuterPathItems().size() < level) {
            return Nodes.getNode(getWrappedRoot(), path.getPathItems());
        }

        Map<String, Object> node = getFromCache(Nodes.toSimpleEscapedPath(splittedPath.getOuterPathItems()));

        if (splittedPath.getInnerPathitems().size() == 0)
            return node;
        else
            return Nodes.getNode(node, splittedPath.getInnerPathitems());
    }

    @Override
    public void persistNode(Path path, Map<String, Object> configNode, Class configurableClass) throws ConfigurationException {

        SplittedPath splittedPath = getSplittedPath(path);

        if (splittedPath == null)
            throw new IllegalArgumentException("Path '" + path + "' is invalid");

        Map<String, Object> clonedNode = (Map<String, Object>) Nodes.deepCloneNode(configNode);

        String levelKey = Nodes.toSimpleEscapedPath(splittedPath.getOuterPathItems());

        // fallback if requested one of top levels
        if (splittedPath.getOuterPathItems().size() < level) {

            removeNodeFromCache(path);
            persistTopLayerToCache(clonedNode, splittedPath.getOuterPathItems());

        } else if (splittedPath.getOuterPathItems().size() > level) {

            Map<String, Object> levelRootNode = (Map<String, Object>) Nodes.deepCloneNode(getFromCache(levelKey));
            Nodes.replaceNode(levelRootNode, clonedNode, splittedPath.getInnerPathitems());
            putIntoCache(levelKey, levelRootNode);

        } else {
            // this should be the one used mostly
            putIntoCache(levelKey, clonedNode);
        }

        // propagate to backend
        super.persistNode(path, configNode, configurableClass);

    }


    /**
     * This should be executed with global lock
     * @param path
     */
    @Override
    public void removeNode(Path path) throws ConfigurationException {

        removeNodeFromCache(path);

        // propagate to storage backend
        super.removeNode(path);

    }


    private void removeNodeFromCache(Path path) {
        SplittedPath splittedPath = getSplittedPath(path);

        String outerPath = Nodes.toSimpleEscapedPath(splittedPath.getOuterPathItems());

        if (splittedPath.getOuterPathItems().size() < level) {

            ArrayList<String> toDelete = getCacheKeySet().stream()
                    .filter(s -> s.startsWith(outerPath))
                    .collect(Collectors.toCollection(ArrayList::new));

            toDelete.forEach(this::removeFromCache);

        } else if (splittedPath.getOuterPathItems().size() > level) {

            Map<String, Object> node = getFromCache(outerPath);

            // if not found - nothing to delete
            if (node==null) return;

            Map<String, Object> levelNode = (Map<String, Object>) Nodes.deepCloneNode(node);
            Nodes.removeNode(levelNode, splittedPath.getInnerPathitems());
            putIntoCache(outerPath, levelNode);

        } else {
            removeFromCache(outerPath);
        }
    }

    private SplittedPath getSplittedPath(Path path) {
        List<Object> pathItems = path.getPathItems();
        if (pathItems == null) return null;
        return new SplittedPath(pathItems, level);
    }

    @Override
    public boolean nodeExists(Path path) throws ConfigurationException {

        SplittedPath splittedPath = getSplittedPath(path);

        if (splittedPath == null)
            throw new IllegalArgumentException("Path '" + path + "' is not valid");

        String outerPath = Nodes.toSimpleEscapedPath(splittedPath.getOuterPathItems());

        int size = splittedPath.getOuterPathItems().size();
        if (size < level) {

            for (String s : getCacheKeySet()) {
                if (s.startsWith(outerPath)) {
                    return true;
                }
            }
            return false;
        } else if (size > level) {
            Map<String, Object> levelNode = getFromCache(outerPath);

            // if parent node not found - no children as well
            if (levelNode==null) return false;

            return Nodes.nodeExists(levelNode, splittedPath.getInnerPathitems());
        } else {

            return getCacheKeySet().contains(outerPath);
        }
    }

    @Override
    public Iterator search(String liteXPathExpression) throws IllegalArgumentException, ConfigurationException {
        ArrayList<Object> objects = new ArrayList<>();
        Nodes.search(getWrappedRoot(), liteXPathExpression).forEachRemaining((e) -> objects.add(Nodes.deepCloneNode(e)));
        return objects.iterator();
    }

    @Override
    public void lock() {
        super.lock();
        long time = System.currentTimeMillis();
        cache.lock(KEYSET_KEY);
        log.debug("Acquiring cache lock took {}", System.currentTimeMillis() - time);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Util to work with cache - including handling keyset and isolation issues
    // Some of this logic should be thrown away as we move to a newer version of infinispan
    ////////////////////////////////////////////////////////////////////////////


    private Map<String, Object> getFromCache(String path) {
        // only return result of get if the key exists in the keyset - otherwise we can face isolation issues while in transaction
        // e.g. after calling 'remove' it will still return the 'read committed' existing value before tx commit

        Map<String, Object> keySet = cache.get(KEYSET_KEY);

        if (keySet== null) return null;

        if (keySet.containsKey(path))
            return cache.get(path);
        else
            return null;
    }

    private void putIntoCache(String key, Map<String, Object> value) {

        // add key to the keyset entry
        Map<String, Object> keysMap = cache.get(KEYSET_KEY);
        if (keysMap == null) {
            keysMap = new HashMap<>();
        } else {
            keysMap = (Map<String, Object>) Nodes.deepCloneNode(keysMap);
        }

        keysMap.put(key, true);
        cache.put(KEYSET_KEY, keysMap);

        cache.put(key, value);
    }



    private Set<String> getCacheKeySet() {
        Map<String, Object> keyMap = cache.get(KEYSET_KEY);
        if (keyMap == null)
            keyMap = Collections.emptyMap();
        return keyMap.keySet();
    }

    private void removeFromCache(String key) {
        Map<String, Object> keysMap = cache.get(KEYSET_KEY);
        if (keysMap != null) {
            keysMap = (Map<String, Object>) Nodes.deepCloneNode(keysMap);
            keysMap.remove(key);
            cache.put(KEYSET_KEY, keysMap);
        }

        cache.remove(key);
    }

    private void clearCache() {
        cache.clear();
        cache.put(KEYSET_KEY, new HashMap<>());
    }
}