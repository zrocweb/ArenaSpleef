package mc.arena.spleef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import mc.alk.arena.objects.MatchState;
import mc.alk.arena.objects.arenas.Arena;
import mc.alk.arena.objects.events.EventPriority;
import mc.alk.arena.objects.events.MatchEventHandler;
import mc.alk.arena.serializers.Persist;
import mc.alk.arena.util.Log;
import mc.alk.arena.util.WorldGuardUtil;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import com.sk89q.worldedit.bukkit.selections.Selection;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

public class SpleefArena extends Arena{

	@Persist
	String worldName; /// What world this spleef is in, loaded from ArenaSpleef/arenas.yml

	@Persist
	List<String> layerNames; /// What are our layer names, loaded from ArenaSpleef/arenas.yml

	@Persist
	Map<String, Integer> regenTimes; /// How often each layer should regenerate, loaded from ArenaSpleef/arenas.yml

	/// The following variables should be reinitialized and set up every match
	Map<String, Integer> regenTimers; /// list of regen timer id's

	List<ProtectedRegion> regions; /// Our protected region layers

	World world; /// what world our spleef is in

	Set<String> lostPlayers = Collections.synchronizedSet(new HashSet<String>());


	@Override
	public void onOpen(){
		localInit();
		regenLayers();
	}

	@Override
	public void onPrestart(){
		regenLayers();
	}

	@Override
	public void onStart(){
		startRegenTimers();
	}

	private void localInit(){
		cancelTimers();
		lostPlayers.clear();
		if (worldName == null){
			Log.err("[ArenaSpleef] worldName was null in arena " + getName() +" Please remake the layers using the setLayer command");
			return;
		}
		if (world == null)
			world = Bukkit.getWorld(worldName);

		if (world == null){
			Log.err("[ArenaSpleef] world was null in arena " + getName() +" Please remake the layers using the setLayer command");
			return;
		}
		if (layerNames == null || layerNames.isEmpty()){
			Log.err("[ArenaSpleef] "+getName()+" layers were empty.  Please remake the layers using the setLayer command");
			return;
		}
		if (regions == null && !initProtectedRegions()){
			Log.err("[ArenaSpleef] "+getName()+" one of the WorldGuard regions was not found, please remake the layers using the setLayer command");
			return;
		}
	}

	public boolean initProtectedRegions(){
		if (world == null && worldName != null)
			world = Bukkit.getWorld(worldName);
		if (layerNames == null || layerNames.isEmpty() || world == null)
			return false;
		regions = new CopyOnWriteArrayList<ProtectedRegion>();
		for (String layerName: layerNames){
			ProtectedRegion pr = WorldGuardUtil.getRegion(world, layerName);
			if (pr == null){
				return false;}
			regions.add(pr);
		}
		return true;
	}

	private void startRegenTimers(){
		if (regenTimes == null)
			return;
		for (String layerName: layerNames){
			if (regenTimes.containsKey(layerName)){
				ProtectedRegion pr = WorldGuardUtil.getRegion(world, layerName);
				startRegenTimer(pr, regenTimes.get(layerName));
			}
		}
	}

	private void startRegenTimer(final ProtectedRegion pr, final Integer time) {
		if (regenTimers == null)
			regenTimers = new ConcurrentHashMap<String, Integer>();
		Integer timerid = regenTimers.remove(pr.getId());
		if (timerid != null){
			Bukkit.getScheduler().cancelTask(timerid);}
		// Set the timer to regenLayerTimes the blocks if someone has walled themselves off, or people cant reach each other
		timerid = Bukkit.getScheduler().scheduleSyncRepeatingTask(ArenaSpleef.getSelf(), new Runnable(){
			public void run() {
				regenLayer(pr);
			}
		}, (long) (time*20L*mc.alk.arena.Defaults.TICK_MULT),
		(long) (time*20L*mc.alk.arena.Defaults.TICK_MULT));
		regenTimers.put(pr.getId(), timerid);
	}

	public void regenLayers(){
		if (regions == null)
			return;
		for (ProtectedRegion pr: regions){
			regenLayer(pr);}
	}

	void regenLayer(ProtectedRegion pr){
		try {
			WorldGuardUtil.pasteSchematic(Bukkit.getConsoleSender(), pr, pr.getId(),world);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onFinish(){
		regenLayers();
		cancelTimers();
	}

	@MatchEventHandler(priority=EventPriority.LOW)
	public void onBlockBreak(BlockBreakEvent event){
		if (regions == null)
			return;
		Location l = event.getBlock().getLocation();
		for (ProtectedRegion pr: regions){
			if (pr.contains(l.getBlockX(), l.getBlockY(),l.getBlockZ())){
				event.setCancelled(false);}
		}
	}

	@MatchEventHandler(priority=EventPriority.LOW)
	public void onPlayerInteract(PlayerInteractEvent event){
		if (regions == null || !Defaults.SUPERPICK || event.getAction() != Action.LEFT_CLICK_BLOCK ||
				!superPickItem(event.getPlayer().getItemInHand()))
			return;
		Location l = event.getClickedBlock().getLocation();
		for (ProtectedRegion pr: regions){
			if (pr.contains(l.getBlockX(), l.getBlockY(),l.getBlockZ())){
				event.setCancelled(true);
				event.getClickedBlock().setType(Material.AIR);
				event.getClickedBlock().getState().update();
			}
		}
	}

	/**
	 * Check player move for the touching water victory condition
	 * @param e
	 */
	@MatchEventHandler(priority=EventPriority.LOW)
	public void onPlayerMove(PlayerMoveEvent e){
		/// Same block, return out
		if(		e.getFrom().getBlockX()==e.getTo().getBlockX() &&
				e.getFrom().getBlockZ()==e.getTo().getBlockZ() &&
				e.getFrom().getBlockY()==e.getTo().getBlockY() ||
				e.getTo().getBlock().getType() != Material.STATIONARY_WATER ||
				getMatchState() != MatchState.ONSTART){
			return;}
		final String name = e.getPlayer().getName();
		/// check to see if the player has lost for the first time
		if (lostPlayers.add(name)){
			Bukkit.getScheduler().scheduleSyncDelayedTask(ArenaSpleef.getSelf(), new Runnable(){
				@Override
				public void run() {
					Player p = Bukkit.getPlayerExact(name);
					if (p.isOnline() && ! p.isDead()){
						PlayerDeathEvent ede = new PlayerDeathEvent(p,new ArrayList<ItemStack>(),0, "");
						Bukkit.getPluginManager().callEvent(ede);
					}
				}
			}, 30L);
		}
	}

	private static boolean superPickItem(ItemStack item) {
		if (item == null)
			return false;
		return item.getTypeId() == Defaults.SUPERPICK_ITEM;
	}

	@Override
	public boolean valid(){
		boolean success = super.valid() && WorldGuardUtil.hasWorldGuard() &&
				layerNames != null && !layerNames.isEmpty() && worldName != null && Bukkit.getWorld(worldName)!=null;
		if (!success)
			return false;
		for (int i=0;i<layerNames.size();i++){
			if (!WorldGuardUtil.hasRegion(Bukkit.getWorld(worldName), getRegionName(i)))
				return false;
		}
		return true;
	}

	@Override
	public List<String> getInvalidReasons(){
		List<String> reasons = new ArrayList<String>();
		if (!WorldGuardUtil.hasWorldGuard()){
			reasons.add("ArenaSpleef needs WorldGuard!");}
		World w = null;
		boolean lostWorld = worldName == null || worldName.isEmpty() || Bukkit.getWorld(worldName)==null;
		if (!lostWorld)
			w = Bukkit.getWorld(worldName);
		if (lostWorld || w== null){
			reasons.add("ArenaSpleef lost its region, please reselect it. world "+worldName+" not found");}

		if (layerNames == null || layerNames.isEmpty()){
			reasons.add("ArenaSpleef arena needs a layer region, and none is defined!");
		} else if (!lostWorld && WorldGuardUtil.hasWorldGuard()){
			for (int i=0;i<layerNames.size();i++){
				if (!WorldGuardUtil.hasRegion(w, getRegionName(i)))
					reasons.add("ArenaSpleef lost layer "+i+", please reselect it");
			}
		}
		reasons.addAll(super.getInvalidReasons());
		return reasons;
	}

	private void cancelTimers() {
		if (regenTimers != null){
			for (Integer timerid: regenTimers.values()){
				Bukkit.getScheduler().cancelTask(timerid);
			}
		}
	}

	private String getRegionName(int layer) {
		return "ba-spleef-"+getName().toLowerCase() +"-"+layer;
	}

	public void setRegion(Player p, Selection sel, int layer) throws Exception, SpleefException {
		final String layerName = getRegionName(layer);
		if (layerNames == null)
			layerNames = new CopyOnWriteArrayList<String>();
		if (layer < layerNames.size()){
			layerNames.set(layer, layerName);
		} else if (layer == layerNames.size()){
			layerNames.add(layerName);
		} else{
			throw new SpleefException("&cYou need to set layer " + (layerNames.size()+1)+" before setting this layer!");
		}
		worldName = sel.getWorld().getName();
		WorldGuardUtil.addRegion(p, layerName);
		ProtectedRegion pr = WorldGuardUtil.getRegion(sel.getWorld(), layerName);
		pr.setPriority(11); /// some relatively high priority
		pr.setFlag(DefaultFlag.PVP,State.DENY);
		pr.setFlag(DefaultFlag.BUILD,State.ALLOW); /// allow them to build on the layer, we will handle

		WorldGuardUtil.saveSchematic(p, layerName);
		initProtectedRegions();
	}

	public void setRegen(int layer, Integer regenTime) throws Exception, SpleefException {
		if (layerNames == null || layer >= layerNames.size()){
			throw new SpleefException("&cYou need to set layer " + layer +" before adding regen to it! "+
					"&6/spleef setLayer <arena> " + layer);
		}
		final String layerName = getRegionName(layer);
		if (regenTimes == null)
			regenTimes = new ConcurrentHashMap<String,Integer>();
		if (regenTime != null && regenTime > 0){
			regenTimes.put(layerName, regenTime);}
	}

	public void deleteRegen(int layer) throws Exception, SpleefException {
		if (layerNames == null || layer >= layerNames.size()){
			throw new SpleefException("&cYou need to set layer " + layer +" before deleting regen !"+
					"&6/spleef setLayer <arena> " + layer);
		}
		final String layerName = getRegionName(layer);
		if (regenTimes == null){
			return;}
		regenTimes.remove(layerName);
	}

	@Override
	protected void delete(){
		if (layerNames != null){
			for (String layerName: layerNames){
				if (world == null)
					continue;
				WorldGuardUtil.deleteRegion(world.getName(), layerName);
			}
		}
	}
}