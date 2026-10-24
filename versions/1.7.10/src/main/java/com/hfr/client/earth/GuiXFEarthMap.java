package com.hfr.client.earth;

import com.hfr.packet.PacketDispatcher;
import com.hfr.main.ClientProxy;
import com.hfr.packet.client.EarthMapRequestPacket;
import com.hfr.world.earth.map.XFEarthMapData;
import com.hfr.client.journeymap.ClientClaimOverlayCache;
import com.hfr.client.journeymap.ClientClaimOverlayCache.Snapshot;
import com.hfr.clowder.ClaimOverlayData.Claim;
import com.hfr.clowder.ClowderTerritory;
import com.hfr.clowder.TerritoryCoordinateBounds;
import com.hfr.clowder.TerritoryCoordinateBounds.Bounds;
import com.hfr.world.earth.map.XFEarthMapLayers.Feature;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.nio.IntBuffer;
import java.util.Locale;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

@SideOnly(Side.CLIENT)
public final class GuiXFEarthMap extends GuiScreen {
    private final GuiScreen parent;
    private boolean introduction;
    private int introPage, introTextTop;
    private java.util.List<String> introShortcuts;
    public GuiXFEarthMap() { this(null, false); }
    public GuiXFEarthMap(GuiScreen parent, boolean introduction) {
        this.parent = parent; this.introduction = introduction;
    }
    private static int nextRequest;
    // LWJGL 2 requires 16 remaining integers even though GL_SCISSOR_BOX returns four.
    private final IntBuffer scissor = BufferUtils.createIntBuffer(16);
    private XFEarthMapData data;
    private EarthMapImages images;
    private EarthMapImages roads;
    private String gripNotice = "GRIP roads: no prepared client resource pack";
    private World requestedWorld;
    private int request, dimension, cacheGeneration;
    private String message = "gui.hfr.earthmap.loading";
    private double zoom = 1, centerX = .5, centerZ = .5;
    private int lastX, lastY;
    private boolean dragging;
    private int roadRequest,roadTicks=39,enabledLayers=63,pressX,pressY,selectX,selectY;private String roadView="",roadNotice="",profileId="";
    private EarthMapLayerCache.Frame layers;private com.hfr.world.earth.map.XFEarthMapLayers.Feature selected;
    private Snapshot claimSnapshot;
    private final java.util.List<com.hfr.client.journeymap.PreparedClaims.Cell> visibleClaims=new java.util.ArrayList<com.hfr.client.journeymap.PreparedClaims.Cell>();

    @Override public void initGui() {
        buttonList.clear();
        int start = width / 2 - 139;
        buttonList.add(new GuiButton(0, start, height - 24, 26, 20, "+"));
        buttonList.add(new GuiButton(1, start + 30, height - 24, 26, 20, "-"));
        buttonList.add(new GuiButton(2, start + 60, height - 24, 86, 20, I18n.format("gui.hfr.earthmap.center")));
        buttonList.add(new GuiButton(3, start + 150, height - 24, 70, 20, I18n.format("gui.hfr.earthmap.full")));
        buttonList.add(new GuiButton(4, start + 224, height - 24, 54, 20, I18n.format("gui.done")));
        String[] labels={"roads","fields","buildings","business","bounds","claims","terrain"};
        int buttonWidth=Math.max(36,(width-20)/4);
        for(int i=0;i<7;i++)buttonList.add(new GuiButton(10+i,left()+(i%4)*buttonWidth,32+(i/4)*20,buttonWidth-3,18,((enabledLayers&(1<<i))!=0?"+ ":"- ")+I18n.format("gui.hfr.earthmap.layer."+labels[i])));
        buttonList.add(new GuiButton(17,left()+3*buttonWidth,52,buttonWidth-3,18,I18n.format("xf.home.help")));
        // The header is useful even when this dimension has no Earth profile.
        buttonList.add(new GuiButton(18,10,7,70,18,I18n.format("xf.home.start")));
        buttonList.add(new GuiButton(19,width-80,7,70,18,I18n.format("xf.home.title")));
        if (introduction) {
            introShortcuts = shortcutLines();
            introTextTop = 52 + introShortcuts.size() * 11 + 6;
            int w = Math.min(400, width - 36), x = (width - w) / 2;
            buttonList.add(new GuiButton(20,x,height-55,(w-6)/2,20,I18n.format("xf.home.start")));
            buttonList.add(new GuiButton(21,x+(w+6)/2,height-55,(w-6)/2,20,I18n.format("xf.map.explore")));
            if (introLines().size() > introCapacity()) {
                buttonList.add(new GuiButton(22,x,height-80,40,18,"<"));
                buttonList.add(new GuiButton(23,x+w-40,height-80,40,18,">"));
            }
        }
        if (requestedWorld != mc.theWorld) requestProfile();
        clampView();
    }
    private void requestProfile() {
        requestedWorld = mc.theWorld; data = null; images = null; roads = null; dragging = false;
        roadView="";roadNotice="";layers=null;selected=null;roadTicks=39;EarthMapLayerCache.cancel();
        message = "gui.hfr.earthmap.loading"; zoom = 1; centerX = centerZ = .5;
        if (mc.thePlayer == null || requestedWorld == null) { message = "gui.hfr.earthmap.unavailable"; return; }
        dimension = requestedWorld.provider.dimensionId; request = ++nextRequest;
        PacketDispatcher.wrapper.sendToServer(new EarthMapRequestPacket(request, dimension));
    }
    public void receive(int token, int dimension, String profile, String identity, String error) {
        if (token != request || dimension != this.dimension || requestedWorld != mc.theWorld) return;
        if (!error.isEmpty()) { message = error; return; }
        cacheGeneration = EarthMapTileCache.generation();
        try {
            data = XFEarthMapData.decode(profile, identity);
            profileId=com.hfr.world.earth.geo.XFEarthGeoDataset.profileIdentity(data.profile);
            images = EarthMapImages.load(data);
            message = "";
        } catch (Exception failure) { images = null; message = "gui.hfr.earthmap.images_missing"; }
        loadGrip();
        clampView();
    }
    private void loadGrip() {
        roads = null;
        if (data == null) return;
        try {
            roads = EarthMapImages.loadRoads(data);
            gripNotice = "GRIP4 / CC0: generalized routes and symbolic widths; world blocks unverified";
        } catch (Exception failure) { gripNotice = "GRIP roads unavailable: " + failure.getMessage(); }
    }
    @Override public void updateScreen() {
        if (mc.theWorld != requestedWorld || mc.theWorld != null && mc.theWorld.provider.dimensionId != dimension) requestProfile();
        Snapshot latestClaims=ClientClaimOverlayCache.get(dimension);
        if(latestClaims!=claimSnapshot){claimSnapshot=latestClaims;if(selected!=null&&selected.layer==5)selected=null;}
        if (data != null && cacheGeneration != EarthMapTileCache.generation()) {
            cacheGeneration = EarthMapTileCache.generation();layers=null;selected=null;roadView="";
            try { images = EarthMapImages.load(data); message = ""; }
            catch (Exception failure) { images = null; message = "gui.hfr.earthmap.images_missing"; }
            loadGrip();
        }
        if(data!=null)requestRoads();
    }
    private void requestRoads(){
        int x0=Math.max(data.profile.getBounds().minimumX,(int)Math.floor(data.profile.getBounds().minimumX+(left()-originX())/mapWidth()*data.profile.getWidth()));
        int z0=Math.max(data.profile.getBounds().minimumZ,(int)Math.floor(data.profile.getBounds().minimumZ+(top()-originZ())/mapHeight()*data.profile.getHeight()));
        int x1=Math.min(data.profile.getBounds().maximumX,(int)Math.floor(data.profile.getBounds().minimumX+(left()+viewWidth()-originX())/mapWidth()*data.profile.getWidth()));
        int z1=Math.min(data.profile.getBounds().maximumZ,(int)Math.floor(data.profile.getBounds().minimumZ+(top()+viewHeight()-originZ())/mapHeight()*data.profile.getHeight()));
        int lod=1;double blocksPerPixel=data.profile.getWidth()/mapWidth();while(lod<65536&&lod<blocksPerPixel)lod*=2;
        int geographicMask=enabledLayers&~32;
        String view=com.hfr.world.earth.roads.XFEarthRoadMap.viewKey(x0,z0,x1,z1,lod,geographicMask);
        if(!view.equals(roadView)){
            EarthMapLayerCache.cancel();roadView=view;
            layers=EarthMapLayerCache.find(requestedWorld,profileId,view);
            if(selected!=null&&selected.layer!=5)selected=null;
        }
        if(++roadTicks<40)return;
        if(EarthMapLayerCache.waiting())return;
        roadTicks=0;roadRequest=++nextRequest;
        if(x1<x0||z1<z0)return;
        long regions=((long)com.hfr.world.earth.geo.XFEarthGeoImporter.region(x1)-com.hfr.world.earth.geo.XFEarthGeoImporter.region(x0)+1)
            *((long)com.hfr.world.earth.geo.XFEarthGeoImporter.region(z1)-com.hfr.world.earth.geo.XFEarthGeoImporter.region(z0)+1);
        if(regions>16){layers=null;if(selected!=null&&selected.layer!=5)selected=null;roadNotice="Overview unavailable at this zoom: view exceeds 16 regions";return;}
        roadNotice="Refreshing geographic overview...";
        EarthMapLayerCache.begin(roadRequest,requestedWorld,data,profileId,view);
        PacketDispatcher.wrapper.sendToServer(new com.hfr.packet.client.EarthRoadRequestPacket(roadRequest,dimension,x0,z0,x1,z1,lod,geographicMask));
    }
    public void invalidateLayers(){layers=null;if(selected!=null&&selected.layer!=5)selected=null;roadView="";roadTicks=39;roadNotice="Dataset selection changed; refreshing geographic overview...";}
    public void receiveLayers(EarthMapLayerCache.Frame frame){
        roadNotice=frame.notice;if(frame.notice.contains("busy"))return;layers=frame;
        if(selected!=null&&selected.layer!=5){com.hfr.world.earth.map.XFEarthMapLayers.Feature replacement=null;for(com.hfr.world.earth.map.XFEarthMapLayers.Feature f:frame.features)if(f.key.equals(selected.key))replacement=f;selected=replacement;}
    }
    public void showInfo(final GuiScreen back){
        mc.displayGuiScreen(new GuiScreen(){
            private int page;
            public void initGui(){buttonList.clear();buttonList.add(new GuiButton(0,width/2-50,height-25,100,20,I18n.format("xf.nav.back")));buttonList.add(new GuiButton(1,10,height-25,60,20,I18n.format("xf.tutorial.back")));buttonList.add(new GuiButton(2,width-70,height-25,60,20,I18n.format("xf.tutorial.next")));}
            protected void actionPerformed(GuiButton button){if(button.id==0)mc.displayGuiScreen(back);else page=Math.max(0,page+(button.id==1?-1:1));}
            protected void keyTyped(char c,int key){if(key==1)mc.displayGuiScreen(back);}
            public boolean doesGuiPauseGame(){return false;}
            public void drawScreen(int x,int y,float ticks){drawDefaultBackground();int capacity=Math.max(1,(height-50)/11);
                String info=I18n.format("xf.map.sources.body").replace("\\n","\n")+"\n\n"+(roads==null?"":roads.credit)+"\n"+(layers==null?"\u00a9 OpenStreetMap contributors / ODbL":layers.attribution)+"\n\n"+gripNotice+"\n"+roadNotice+(selected==null?"":"\n\n"+selected.name+"\n"+selected.source+"\n"+selected.detail);
                java.util.List<?> lines=fontRendererObj.listFormattedStringToWidth(info,width-20);page=Math.min(page,Math.max(0,(lines.size()-1)/capacity));
                for(int i=page*capacity,row=12;i<Math.min(lines.size(),(page+1)*capacity);i++,row+=11)drawString(fontRendererObj,lines.get(i).toString(),10,row,0xffffff);super.drawScreen(x,y,ticks);
            }
        });
    }
    private java.util.List<String> introLines() {
        return fontRendererObj.listFormattedStringToWidth(I18n.format("xf.map.intro").replace("\\n","\n"), Math.min(400,width-36));
    }
    private String keyName(KeyBinding binding) {
        return binding.getKeyCode() == 0 ? I18n.format("xf.key.unbound") : GameSettings.getKeyDisplayString(binding.getKeyCode());
    }
    private java.util.List<String> shortcutLines() {
        int w = Math.min(400, width - 36);
        java.util.List<String> lines = new java.util.ArrayList<String>();
        lines.addAll(fontRendererObj.listFormattedStringToWidth(I18n.format("xf.map.open_map", keyName(ClientProxy.xenofactionsMap)), w));
        lines.addAll(fontRendererObj.listFormattedStringToWidth(I18n.format("xf.map.open_menu", keyName(ClientProxy.xenofactionsMenu)), w));
        return lines;
    }
    private int introCapacity() { return Math.max(1,(height-86-introTextTop)/11); }
    private void dismissIntroduction() {
        if (introduction) { introduction=false; com.hfr.client.XFLoginFlow.complete(); }
    }
    @Override public void onGuiClosed(){dismissIntroduction();EarthMapLayerCache.cancel();dragging=false;}
    @Override protected void keyTyped(char c,int key) { if(key==1){dismissIntroduction();mc.displayGuiScreen(parent);} }
    @Override public boolean doesGuiPauseGame() { return false; }
    private int left() { return 10; }
    private int top() { return 76; }
    private int viewWidth() { return Math.max(1, width - 20); }
    private int viewHeight() { return Math.max(1, height - 148); }
    private double mapWidth() { return data.profile.getWidth() * fit() * zoom; }
    private double mapHeight() { return data.profile.getHeight() * fit() * zoom; }
    private double fit() { return Math.min((double)viewWidth() / data.profile.getWidth(), (double)viewHeight() / data.profile.getHeight()); }
    private double originX() { return left() + viewWidth() / 2.0 - centerX * mapWidth(); }
    private double originZ() { return top() + viewHeight() / 2.0 - centerZ * mapHeight(); }
    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
    private void clampView() {
        if (data == null) return;
        // Permit the viewport to overhang an edge so Center on you really centers edge positions.
        centerX = clamp(centerX, 0, 1); centerZ = clamp(centerZ, 0, 1);
    }
    private boolean inside(int x, int y) { return x >= left() && x < left() + viewWidth() && y >= top() && y < top() + viewHeight(); }
    private void changeZoom(double factor, int x, int y) {
        if (data == null) return;
        double u = (x - originX()) / mapWidth(), v = (y - originZ()) / mapHeight();
        zoom = clamp(zoom * factor, 1, 32);
        centerX = u - (x - left() - viewWidth() / 2.0) / mapWidth();
        centerZ = v - (y - top() - viewHeight() / 2.0) / mapHeight();
        EarthMapLayerCache.cancel();
        clampView();
    }
    @Override protected void actionPerformed(GuiButton button) {
        if (button.id == 20 || button.id == 18) { dismissIntroduction(); mc.displayGuiScreen(new com.hfr.inventory.gui.GUIXenofactionsTutorial(this)); return; }
        if (button.id == 21) { dismissIntroduction(); initGui(); return; }
        if (button.id == 22 || button.id == 23) { introPage=Math.max(0,Math.min((introLines().size()-1)/introCapacity(),introPage+(button.id==22?-1:1))); return; }
        if (button.id == 19) { mc.displayGuiScreen(new com.hfr.inventory.gui.GUIXenofactionsHome()); return; }
        if (button.id == 4) { mc.displayGuiScreen(parent); return; }
        if(button.id==17){mc.displayGuiScreen(new com.hfr.inventory.gui.GUIXFHelp(this));return;}
        if(button.id>=10&&button.id<17){enabledLayers^=1<<(button.id-10);button.displayString=((enabledLayers&(1<<(button.id-10)))!=0?"+ ":"- ")+button.displayString.substring(2);selected=null;if(button.id!=15){EarthMapLayerCache.cancel();roadView="";roadTicks=39;}return;}
        if (data == null) return;
        if (button.id == 0 || button.id == 1) changeZoom(button.id == 0 ? 1.5 : 1 / 1.5, width / 2, top() + viewHeight() / 2);
        if (button.id == 2 && mc.thePlayer != null) {
            centerX = data.mapX(mc.thePlayer.posX); centerZ = data.mapZ(mc.thePlayer.posZ); clampView();
        }
        if (button.id == 3) { zoom = 1; centerX = centerZ = .5; }
        EarthMapLayerCache.cancel();
    }
    @Override protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        if (!introduction && mc.currentScreen==this && button == 0 && data != null && inside(x, y)) { dragging = true; lastX = pressX = x; lastY = pressY = y; }
    }
    @Override protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (dragging && button == 0 && data != null) {
            centerX -= (x - lastX) / mapWidth(); centerZ -= (y - lastY) / mapHeight();
            EarthMapLayerCache.cancel();
            lastX = x; lastY = y; clampView();
        }
    }
    @Override protected void mouseMovedOrUp(int x, int y, int button) {
        super.mouseMovedOrUp(x,y,button);
        if(button==0){if(dragging&&inside(x,y)&&Math.hypot(x-pressX,y-pressY)<4){
            double u=(x-originX())/mapWidth(),v=(y-originZ())/mapHeight();
            selected=layers==null?null:layers.select(u,v,5/mapWidth(),5/mapHeight(),enabledLayers,selected!=null&&Math.hypot(x-selectX,y-selectY)<4?selected.key:null);
            if(selected==null)selected=selectClaim(u,v);
            selectX=x;selectY=y;
        }dragging=false;}
    }
    @Override public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (!introduction && wheel != 0 && inside(x, y)) changeZoom(wheel > 0 ? 1.25 : 1 / 1.25, x, y);
    }
    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("item.earth_map.name"), width / 2, 7, 0xffffff);
        drawRect(left() - 1, top() - 1, left() + viewWidth() + 1, top() + viewHeight() + 1, 0xff777777);
        drawRect(left(), top(), left() + viewWidth(), top() + viewHeight(), 0xff182333);
        if (data != null && requestedWorld == mc.theWorld) drawMap();
        else drawStatus(message);
        if (mc.thePlayer != null) {
            double x = mc.thePlayer.posX, z = mc.thePlayer.posZ;
            String coords = String.format(Locale.ROOT, "X: %.1f   Z: %.1f", x, z);
            drawCenteredString(fontRendererObj, coords, width / 2, height - 65, 0xffffff);
            if (data != null && data.hasGeographicConversion() && data.contains(x, z))
                drawCenteredString(fontRendererObj, I18n.format("gui.hfr.earthmap.geographic",
                        String.format(Locale.ROOT, "%.5f", data.latitude(z)), String.format(Locale.ROOT, "%.5f", data.longitude(x))), width / 2, height - 53, 0xffffff);
            else if (data != null && !data.contains(x, z))
                drawCenteredString(fontRendererObj, I18n.format("gui.hfr.earthmap.outside"), width / 2, height - 53, 0xffcc66);
        }
        drawCenteredString(fontRendererObj, fontRendererObj.trimStringToWidth(I18n.format("gui.hfr.earthmap.controls"),width-16), width / 2, height - 37, 0xaaaaaa);
        if (introduction) {
            int w=Math.min(400,width-36),x=(width-w)/2;
            drawRect(x-8,20,x+w+8,height-29,0xf0182333);
            drawCenteredString(fontRendererObj,I18n.format("xf.map.welcome"),width/2,32,0xffdd88);
            int shortcutY = 52;
            for (String line : introShortcuts) {
                drawString(fontRendererObj, line, x, shortcutY, 0xffffff);
                shortcutY += 11;
            }
            java.util.List<String> lines=introLines();
            introPage=Math.min(introPage,Math.max(0,(lines.size()-1)/introCapacity()));
            for(int i=introPage*introCapacity(),y=introTextTop;i<Math.min(lines.size(),(introPage+1)*introCapacity());i++,y+=11)
                drawString(fontRendererObj,lines.get(i),x,y,0xeeeeee);
        }
        for (Object object : buttonList) {
            GuiButton button = (GuiButton)object;
            button.visible = !introduction || button.id>=20;
            button.enabled = button.id>=17 || button.id==4 || data!=null;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    private void drawStatus(String key) {
        java.util.List<?> lines = fontRendererObj.listFormattedStringToWidth(I18n.format(key), Math.max(1, viewWidth() - 16));
        int y = top() + (viewHeight() - lines.size() * 11) / 2;
        for (Object line : lines) { drawCenteredString(fontRendererObj, line.toString(), width / 2, y, 0xffffff); y += 11; }
    }
    private void drawMap() {
        boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        // Native writes do not advance the position; restore the four values with indexed reads.
        scissor.clear(); GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissor);
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(left() * scale, mc.displayHeight - (top() + viewHeight()) * scale, viewWidth() * scale, viewHeight() * scale);
            double w = mapWidth(), h = mapHeight(), ox = originX(), oz = originZ();
            if(images!=null){
            int level = 0;
            for (int i = 1; i < images.levels.length; i++) {
                EarthMapImages.Level candidate = images.levels[i];
                // Keep a whole visible level within the 32-tile texture budget, including square maps.
                if ((long)((candidate.width + 255) / 256) * ((candidate.height + 255) / 256) <= imageryTileBudget()
                        && images.levels[level].width < w) level = i;
            }
            EarthMapImages.Level size = images.levels[level];
            int minX = Math.max(0, (int)Math.floor((left() - ox) / w * size.width / 256));
            int minZ = Math.max(0, (int)Math.floor((top() - oz) / h * size.height / 256));
            int maxX = Math.min((size.width - 1) / 256, (int)Math.floor((left() + viewWidth() - ox) / w * size.width / 256));
            int maxZ = Math.min((size.height - 1) / 256, (int)Math.floor((top() + viewHeight() - oz) / h * size.height / 256));
            boolean loading = false;
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                ResourceLocation texture = EarthMapTileCache.get(data, images, level, x, z);
                if (texture == null) { loading = true; continue; }
                mc.getTextureManager().bindTexture(texture); GL11.glColor4f(1, 1, 1, 1);
                double x0 = ox + x * 256.0 / size.width * w, z0 = oz + z * 256.0 / size.height * h;
                double x1 = ox + Math.min(size.width, (x + 1) * 256.0) / size.width * w;
                double z1 = oz + Math.min(size.height, (z + 1) * 256.0) / size.height * h;
                Tessellator t = Tessellator.instance; t.startDrawingQuads();
                t.addVertexWithUV(x0, z1, 0, 0, 1); t.addVertexWithUV(x1, z1, 0, 1, 1);
                t.addVertexWithUV(x1, z0, 0, 1, 0); t.addVertexWithUV(x0, z0, 0, 0, 0); t.draw();
            }
            if (loading) drawString(fontRendererObj, I18n.format(EarthMapTileCache.hasFailure()
                    ? "gui.hfr.earthmap.images_missing" : "gui.hfr.earthmap.loading"), left() + 4, top() + 4, 0xffffff);
            }else drawString(fontRendererObj,I18n.format("gui.hfr.earthmap.images_missing"),left()+4,top()+37,0xffcc66);
            if (roads != null && (enabledLayers & 1) != 0) drawGrip(ox, oz, w, h);
            drawClaims(ox,oz,w,h);
            if(layers!=null)layers.draw(ox,oz,w,h,enabledLayers);
            String claimNotice=ClientClaimOverlayCache.notice();
            drawString(fontRendererObj,fontRendererObj.trimStringToWidth(I18n.format(claimNotice.isEmpty()?"xf.map.legend":"xf.map.claims_stale"),viewWidth()-8),left()+4,top()+4,0xffffff);
            if((enabledLayers&64)!=0)drawString(fontRendererObj,fontRendererObj.trimStringToWidth(I18n.format("xf.map.terrain_legend"),viewWidth()-8),left()+4,top()+15,0xffffff);


            drawString(fontRendererObj,"\u00a9 OpenStreetMap contributors / ODbL",left()+4,top()+viewHeight()-50,0xeeeeee);
            if(selected!=null){
                String status=I18n.format(selected.layer==5?"xf.map.claim_detail":selected.layer==6?"xf.map.terrain_detail":"xf.map.feature_detail");
                String title=selected.name.isEmpty()?I18n.format("xf.map.unnamed"):selected.name;
                drawRect(left()+2,top()+viewHeight()-38,left()+viewWidth()-2,top()+viewHeight()-2,0xcc000000);
                drawString(fontRendererObj,fontRendererObj.trimStringToWidth(title,viewWidth()-8),left()+4,top()+viewHeight()-36,0xffffff);
                drawString(fontRendererObj,fontRendererObj.trimStringToWidth(status,viewWidth()-8),left()+4,top()+viewHeight()-25,0xdddddd);
                drawString(fontRendererObj,fontRendererObj.trimStringToWidth(I18n.format("xf.map.more"),viewWidth()-8),left()+4,top()+viewHeight()-14,0xdddddd);
            }
            if (mc.thePlayer != null) {
                double actualX = ox + data.mapX(mc.thePlayer.posX) * w, actualZ = oz + data.mapZ(mc.thePlayer.posZ) * h;
                int px = (int)clamp(actualX, left() + 5, left() + viewWidth() - 6);
                int pz = (int)clamp(actualZ, top() + 5, top() + viewHeight() - 6);
                drawRect(px - 4, pz - 4, px + 5, pz + 5, 0xff000000);
                drawRect(px - 2, pz - 2, px + 3, pz + 3, 0xffff4444);
                String label = I18n.format("gui.hfr.earthmap.you");
                if (actualX < left() || actualX > left() + viewWidth() || actualZ < top() || actualZ > top() + viewHeight())
                    label += " " + I18n.format("gui.hfr.earthmap.offscreen");
                int length = fontRendererObj.getStringWidth(label);
                int lx = (int)clamp(px + 8, left() + 2, left() + viewWidth() - length - 2);
                int ly = (int)clamp(pz - 13, top() + 2, top() + viewHeight() - 11);
                drawRect(lx - 1, ly - 1, lx + length + 1, ly + 10, 0xcc000000);
                drawString(fontRendererObj, label, lx, ly, 0xffffff);
            }
        } finally {
            GL11.glScissor(scissor.get(0), scissor.get(1), scissor.get(2), scissor.get(3));
            if (wasScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColor4f(1, 1, 1, 1);
        }
    }
    private int imageryTileBudget() {
        if (roads == null) return 32;
        EarthMapImages.Level largest = roads.levels[roads.levels.length-1];
        return 32 - ((largest.width+255)/256) * ((largest.height+255)/256);
    }
    private Feature selectClaim(double u,double v) {
        Snapshot snapshot=ClientClaimOverlayCache.get(dimension);
        if(snapshot==null||(enabledLayers&32)==0)return null;
        int x=(int)Math.floor(data.profile.getBounds().minimumX+u*data.profile.getWidth());
        int z=(int)Math.floor(data.profile.getBounds().minimumZ+v*data.profile.getHeight());
        ClowderTerritory.CoordPair coordinate=ClowderTerritory.getCoordPair(dimension,x,z);
        Claim claim=snapshot.byChunk.get(com.hfr.clowder.ClaimOverlayData.chunkKey(coordinate.x,coordinate.z));
        if(claim==null)return null;
        Bounds bx=TerritoryCoordinateBounds.forCoordinate(claim.chunkX),bz=TerritoryCoordinateBounds.forCoordinate(claim.chunkZ);
        return new Feature("claim:"+claim.chunkX+","+claim.chunkZ,claim.label,"Claim","Territorial ownership","Ownership is independent of terrain diagnostics",5,2,claim.color,0,
            new double[][]{{bx.minInclusive,bz.minInclusive,bx.maxExclusive,bz.minInclusive,bx.maxExclusive,bz.maxExclusive,bx.minInclusive,bz.maxExclusive}});
    }
    private void drawClaims(double ox,double oz,double w,double h) {
        if((enabledLayers&32)==0)return;
        Snapshot snapshot=ClientClaimOverlayCache.get(dimension);if(snapshot==null||w<=0||h<=0)return;
        double worldX=data.profile.getBounds().minimumX,worldZ=data.profile.getBounds().minimumZ;
        snapshot.prepared.select(worldX+(left()-ox)/w*data.profile.getWidth(),worldZ+(top()-oz)/h*data.profile.getHeight(),
            worldX+(left()+viewWidth()-ox)/w*data.profile.getWidth(),worldZ+(top()+viewHeight()-oz)/h*data.profile.getHeight(),visibleClaims,null);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT|GL11.GL_COLOR_BUFFER_BIT|GL11.GL_CURRENT_BIT|GL11.GL_LINE_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);GL11.glLineWidth(1F);
            for(int pass=0;pass<2;pass++){
                GL11.glBegin(pass==0?GL11.GL_QUADS:GL11.GL_LINES);
                try { for(int i=0;i<visibleClaims.size();i++) {
                    com.hfr.client.journeymap.PreparedClaims.Cell cell=visibleClaims.get(i);int color=cell.claim.color;
                    double x0=ox+data.mapX(cell.x0)*w,x1=ox+data.mapX(cell.x1)*w;
                    double z0=oz+data.mapZ(cell.z0)*h,z1=oz+data.mapZ(cell.z1)*h;
                    GL11.glColor4f((color>>16&255)/255F,(color>>8&255)/255F,(color&255)/255F,pass==0?.35F:.95F);
                    if(pass==0){GL11.glVertex2d(x0,z0);GL11.glVertex2d(x1,z0);GL11.glVertex2d(x1,z1);GL11.glVertex2d(x0,z1);}
                    else {
                        if((cell.edges&1)!=0){GL11.glVertex2d(x0,z0);GL11.glVertex2d(x1,z0);}
                        if((cell.edges&2)!=0){GL11.glVertex2d(x1,z0);GL11.glVertex2d(x1,z1);}
                        if((cell.edges&4)!=0){GL11.glVertex2d(x1,z1);GL11.glVertex2d(x0,z1);}
                        if((cell.edges&8)!=0){GL11.glVertex2d(x0,z1);GL11.glVertex2d(x0,z0);}
                    }
                }} finally {GL11.glEnd();}
            }
        } finally {GL11.glPopAttrib();visibleClaims.clear();}
    }
    private void drawGrip(double ox, double oz, double w, double h) {
        int level = w > roads.levels[0].width ? roads.levels.length - 1 : 0;
        EarthMapImages.Level size = roads.levels[level];
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            for (int z = 0; z < (size.height + 255) / 256; z++) for (int x = 0; x < (size.width + 255) / 256; x++) {
                double x0 = ox + x * 256.0 / size.width * w, z0 = oz + z * 256.0 / size.height * h;
                double x1 = ox + Math.min(size.width, (x+1)*256.0) / size.width * w;
                double z1 = oz + Math.min(size.height, (z+1)*256.0) / size.height * h;
                if (x1 < left() || z1 < top() || x0 > left()+viewWidth() || z0 > top()+viewHeight()) continue;
                ResourceLocation texture = EarthMapTileCache.get(data, roads, level, x, z);
                if (texture == null) continue;
                mc.getTextureManager().bindTexture(texture); GL11.glColor4f(1,1,1,1);
                Tessellator t = Tessellator.instance; t.startDrawingQuads();
                t.addVertexWithUV(x0,z1,0,0,1); t.addVertexWithUV(x1,z1,0,1,1);
                t.addVertexWithUV(x1,z0,0,1,0); t.addVertexWithUV(x0,z0,0,0,0); t.draw();
            }
        } finally { GL11.glPopAttrib(); }
    }
}

