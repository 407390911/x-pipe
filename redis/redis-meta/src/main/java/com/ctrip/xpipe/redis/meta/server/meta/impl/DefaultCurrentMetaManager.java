package com.ctrip.xpipe.redis.meta.server.meta.impl;


import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unidal.tuple.Pair;

import com.ctrip.xpipe.api.observer.Observable;
import com.ctrip.xpipe.api.observer.Observer;
import com.ctrip.xpipe.concurrent.AbstractExceptionLogTask;
import com.ctrip.xpipe.observer.AbstractLifecycleObservable;
import com.ctrip.xpipe.observer.NodeAdded;
import com.ctrip.xpipe.observer.NodeDeleted;
import com.ctrip.xpipe.redis.core.entity.ClusterMeta;
import com.ctrip.xpipe.redis.core.entity.KeeperMeta;
import com.ctrip.xpipe.redis.core.entity.RedisMeta;
import com.ctrip.xpipe.redis.core.meta.MetaComparator;
import com.ctrip.xpipe.redis.core.meta.comparator.ClusterMetaComparator;
import com.ctrip.xpipe.redis.core.meta.comparator.DcMetaComparator;
import com.ctrip.xpipe.redis.meta.server.cluster.CurrentClusterServer;
import com.ctrip.xpipe.redis.meta.server.cluster.SlotManager;
import com.ctrip.xpipe.redis.meta.server.meta.CurrentMeta;
import com.ctrip.xpipe.redis.meta.server.meta.CurrentMetaManager;
import com.ctrip.xpipe.redis.meta.server.meta.DcMetaCache;
import com.ctrip.xpipe.utils.XpipeThreadFactory;


/**
 * @author wenchao.meng
 *
 * Aug 6, 2016
 */
@Component
public class DefaultCurrentMetaManager extends AbstractLifecycleObservable implements CurrentMetaManager, Observer{
	
	private int slotCheckInterval = 60000;
	
	@Autowired
	private SlotManager slotManager;
	
	@Autowired
	private CurrentClusterServer currentClusterServer;
	
	@Autowired
	private DcMetaCache dcMetaCache;
	
	private CurrentMeta currentMeta = new CurrentMeta();;
	
	private Set<Integer>   currentSlots = new HashSet<>();
	
	private ScheduledExecutorService scheduled;
	private ScheduledFuture<?> 		slotCheckFuture;
	
	
	@Override
	protected void doInitialize() throws Exception {
		super.doInitialize();

		dcMetaCache.addObserver(this);
		scheduled = Executors.newScheduledThreadPool(2, XpipeThreadFactory.create(String.format("CURRENT_META_MANAGER(%d)", currentClusterServer.getServerId())));
	}
	
	@Override
	protected void doStart() throws Exception {
		super.doStart();
		
		for(Integer slotId : currentClusterServer.slots()){
			addSlot(slotId);
		}
		
		
		slotCheckFuture = scheduled.scheduleWithFixedDelay(new AbstractExceptionLogTask() {
			
			@Override
			protected void doRun() {
				checkAddOrRemoveSlots();
			}
		}, slotCheckInterval, slotCheckInterval, TimeUnit.SECONDS);
	}

	
	protected void checkAddOrRemoveSlots() {
		
		
		Set<Integer> slots = slotManager.getSlotsByServerId(currentClusterServer.getServerId(), false);
		
		Pair<Set<Integer>, Set<Integer>> result = getAddAndRemove(slots, currentSlots);
		
		for(Integer slotId : result.getKey()){
			addSlot(slotId);
		}

		for(Integer slotId : result.getValue()){
			deleteSlot(slotId);
		}
	}


	protected Pair<Set<Integer>, Set<Integer>> getAddAndRemove(Set<Integer> future, Set<Integer> current) {
		
		Set<Integer> added = new HashSet<>(future);
		added.removeAll(current);
		
		if(added.size() > 0){
			logger.info("[checkAddOrRemoveSlots][to add]{}", added);
		}
		
		Set<Integer> toRemove = new HashSet<>(current);
		toRemove.removeAll(future);

		if(toRemove.size() > 0){
			logger.info("[checkAddOrRemoveSlots][toRemove]{}", toRemove);
		}
		
		return new Pair<>(added, toRemove);
	}


	@Override
	protected void doStop() throws Exception {
		
		slotCheckFuture.cancel(true);
		super.doStop();
	}
	
	
	@Override
	protected void doDispose() throws Exception {
		
		scheduled.shutdownNow();
		super.doDispose();
	}
	
	private void handleClusterChanged(ClusterMetaComparator clusterMetaComparator) {
		
		String clusterId = clusterMetaComparator.getCurrent().getId();
		if(currentMeta.hasCluster(clusterId)){
			
			currentMeta.changeCluster(clusterMetaComparator);
			notifyObservers(clusterMetaComparator);
		}else{
			logger.warn("[handleClusterChanged][but we do not has it]{}", clusterMetaComparator);
			addCluster(clusterId);
		}
	}


	private void addCluster(String clusterId) {
		
		ClusterMeta clusterMeta = dcMetaCache.getClusterMeta(clusterId);
		
		logger.info("[addCluster]{}, {}", clusterId, clusterMeta);
		currentMeta.addCluster(clusterMeta);
		notifyObservers(new NodeAdded<ClusterMeta>(clusterMeta));
	}

	private void destroyCluster(ClusterMeta clusterMeta){
		//keeper in clustermeta, keepermanager remove keeper
		removeCluster(clusterMeta);
	}
	
	private void removeCluster(ClusterMeta clusterMeta) {
		
		logger.info("[removeCluster]{}", clusterMeta.getId());
		boolean result = currentMeta.removeCluster(clusterMeta.getId()) != null;
		if(result){
			notifyObservers(new NodeDeleted<ClusterMeta>(clusterMeta));
		}
	}



	private void removeClusterInterested(String clusterId) {
		//notice
		removeCluster(new ClusterMeta(clusterId));
	}

	@Override
	public Set<String> allClusters() {
		return new HashSet<>(currentMeta.allClusters());
	}

	@Override
	public void deleteSlot(int slotId) {
		
		currentSlots.remove(slotId);
		logger.info("[deleteSlot]{}", slotId);
		for(String clusterId : new HashSet<>(currentMeta.allClusters())){
			
			int currentSlotId = slotManager.getSlotIdByKey(clusterId);
			if(currentSlotId == slotId){
				removeClusterInterested(clusterId);
			}
		}
	}


	@Override
	public void addSlot(int slotId) {
		
		logger.info("[addSlot]{}", slotId);
		currentSlots.add(slotId);
		for(String clusterId : dcMetaCache.getClusters()){
			
			int currentSlotId = slotManager.getSlotIdByKey(clusterId);
			if(currentSlotId == slotId){
				addCluster(clusterId);
			}
		}
	}

	@Override
	public void exportSlot(int slotId) {
		
		logger.info("[exportSlot]{}", slotId);
		deleteSlot(slotId);
	}

	@Override
	public void importSlot(int slotId) {
		
		logger.info("[importSlot][doNothing]{}", slotId);
	}

	@Override
	public void update(Object args, Observable observable) {
		
		if(args instanceof DcMetaComparator){
			dcMetaChange((DcMetaComparator)args);
		}else{
			throw new IllegalArgumentException(String.format("unknown args(%s):%s", args.getClass(), args));
		}
	}

	private void dcMetaChange(DcMetaComparator comparator) {
		
		for(ClusterMeta clusterMeta : comparator.getAdded()){
			if(currentClusterServer.hasKey(clusterMeta.getId())){
				addCluster(clusterMeta.getId());
			}else{
				logger.info("[dcMetaChange][add][not interested]{}", clusterMeta.getId());
			}
		}
		
		for(ClusterMeta clusterMeta : comparator.getRemoved()){
			if(currentClusterServer.hasKey(clusterMeta.getId())){
				destroyCluster(clusterMeta);
			}else{
				logger.info("[dcMetaChange][destroy][not interested]{}", clusterMeta.getId());
			}

		}
		
		for(@SuppressWarnings("rawtypes") MetaComparator changedComparator : comparator.getMofified()){
			ClusterMetaComparator clusterMetaComparator = (ClusterMetaComparator) changedComparator;
			String clusterId = clusterMetaComparator.getCurrent().getId();
			if(currentClusterServer.hasKey(clusterId)){
				handleClusterChanged(clusterMetaComparator);
			}else{
				logger.info("[dcMetaChange][change][not interested]{}", clusterId);
			}
		}
	}

	
	@Override
	public boolean hasCluster(String clusterId) {
		return currentMeta.hasCluster(clusterId);
	}

	@Override
	public boolean hasShard(String clusterId, String shardId) {
		return currentMeta.hasShard(clusterId, shardId);
	}
	
	@Override
	public RedisMeta getRedisMaster(String clusterId, String shardId) {
		return ((DefaultDcMetaCache)dcMetaCache).getDcMeta().getRedisMaster(clusterId, shardId);
	}


	@Override
	public String getUpstream(String clusterId, String shardId) {
		return ((DefaultDcMetaCache)dcMetaCache).getDcMeta().getUpstream(clusterId, shardId);
	}


	@Override
	public List<KeeperMeta> getKeepers(String clusterId, String shardId) {
		return ((DefaultDcMetaCache)dcMetaCache).getDcMeta().getKeepers(clusterId, shardId);
	}

	@Override
	public ClusterMeta getClusterMeta(String clusterId) {
		return dcMetaCache.getClusterMeta(clusterId);
	}


	@Override
	public List<KeeperMeta> getSurviveKeepers(String clusterId, String shardId) {
		return currentMeta.getSurviveKeepers(clusterId, shardId);
	}

	@Override
	public KeeperMeta getKeeperActive(String clusterId, String shardId) {
		return currentMeta.getKeeperActive(clusterId, shardId);
	}
	
	/*******************update dynamic info*************************/
	@Override
	public boolean updateKeeperActive(String clusterId, String shardId, KeeperMeta activeKeeper) {
		return currentMeta.setKeeperActive(clusterId, shardId, activeKeeper);
	}

	@Override
	public void noneKeeperActive(String clusterId, String shardId) {
		currentMeta.setKeeperActive(clusterId, shardId, null);
	}

	@Override
	public void setSurviveKeepers(String clusterId, String shardId, List<KeeperMeta> surviceKeepers, KeeperMeta activeKeeper) {
		currentMeta.setSurviveKeepers(clusterId, shardId, surviceKeepers, activeKeeper);
	}
	
	@Override
	public String toString() {
		return currentMeta.toString();
	}

	@Override
	public boolean watchIfNotWatched(String clusterId, String shardId) {
		return currentMeta.watchIfNotWatched(clusterId, shardId);
	}
}
