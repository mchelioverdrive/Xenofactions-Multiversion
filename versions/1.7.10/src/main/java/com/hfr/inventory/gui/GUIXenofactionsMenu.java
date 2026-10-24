package com.hfr.inventory.gui;
import cpw.mods.fml.client.config.GuiButtonExt; import java.util.*; import com.hfr.packet.PacketDispatcher; import com.hfr.packet.client.XFMenuDataRequestPacket; import com.hfr.xfgui.*; import com.hfr.xfgui.XFCommandArgument.Type; import net.minecraft.client.*; import net.minecraft.client.gui.*; import net.minecraft.util.*; import org.lwjgl.input.Mouse; import org.lwjgl.opengl.GL11; import cpw.mods.fml.relauncher.*;
@SideOnly(Side.CLIENT) public class GUIXenofactionsMenu extends GuiScreen implements GuiYesNoCallback { private static XFMenuData data=new XFMenuData(); private static boolean pending; private static int nextRequest; private static int pendingRequest; private static long pendingSession; private final long session=XFMenuNavigation.session(); private boolean ready; private GuiTextField search; private final Map<String,GuiTextField> fields=new HashMap<String,GuiTextField>(); private XFCommandDefinition.Category cat=XFCommandDefinition.Category.FACTION; private XFCommandDefinition selected; private int page; private int argumentPage; private String validation="";
 private XFMenuData filteredData; private XFCommandDefinition.Category filteredCategory;
 private String filteredQuery; private int filteredGeneration=-1;
 private List<XFCommandDefinition> filtered=Collections.emptyList();
 private final Map<XFCommandDefinition,String> searchText=new HashMap<XFCommandDefinition,String>();
 private int keyboardButton=-1; private boolean awaitingSetup;
 private String cachedCommand; private GuiScreen parent; private String initialAction; private boolean browsing=true;

 public static void updateSession(){
  if(XFMenuNavigation.updateSession(Minecraft.getMinecraft())){data=new XFMenuData();pending=false;pendingRequest=0;}
 }
 public static void requestSessionReset(){XFMenuNavigation.requestReset();}
 public static void requestAndOpen(){requestAndOpen(new GUIXenofactionsHome(),null);}
 public static void requestAndOpen(GuiScreen parent,String action){
  Minecraft mc=Minecraft.getMinecraft(); updateSession();
  if(mc.theWorld==null||mc.thePlayer==null||mc.currentScreen instanceof GUIXenofactionsMenu)return;
  GUIXenofactionsMenu menu=new GUIXenofactionsMenu(); menu.parent=parent; menu.initialAction=action;
  mc.displayGuiScreen(menu); menu.requestData();
 }
 private void requestData(){
  rememberLocation(); ready=false; selected=null; fields.clear(); validation="";
  pending=true; pendingSession=session; pendingRequest=++nextRequest;
  data=new XFMenuData(); initGui();
  PacketDispatcher.wrapper.sendToServer(new XFMenuDataRequestPacket(pendingRequest));
 }
 public static void receiveMenuData(XFMenuData d,int request){
  updateSession();
  if(!pending||request!=pendingRequest||pendingSession!=XFMenuNavigation.session())return;
  data=d==null?new XFMenuData():d; pending=false;
  GuiScreen screen=Minecraft.getMinecraft().currentScreen;
  if(screen instanceof GUIXenofactionsMenu){
   GUIXenofactionsMenu menu=(GUIXenofactionsMenu)screen;
   if(menu.session==XFMenuNavigation.session()){menu.ready=true;menu.restoreLocation(XFMenuNavigation.category());menu.openInitialAction();}
  }
 }
 private boolean currentSession(){updateSession();return session==XFMenuNavigation.session();}
 private void rememberLocation(){
  if(ready&&currentSession())XFMenuNavigation.remember(cat,new XFMenuNavigation.Location(browsing||selected==null?null:selected.id,search==null?"":search.getText(),page,argumentPage));
 }
 private void restoreLocation(XFCommandDefinition.Category category){
  XFMenuNavigation.Location location=XFMenuNavigation.location(category);
  cat=category; search.setText(location.search); page=location.page; argumentPage=location.argumentPage;
  selected=null; validation="";
  for(XFCommandDefinition d:list())if(d.id.equals(location.commandId)&&canRestore(d)){selected=d;break;}
  if(selected==null)argumentPage=0;
  browsing=selected==null; initGui(); rememberLocation();
 }
 private boolean canRestore(XFCommandDefinition d){
  if(d.factionRank==null)return true;
  int rank;try{rank=Integer.parseInt(data.currentRank);}catch(NumberFormatException e){return false;}
  int required="Leader".equals(d.factionRank)||("ClaimRename".equals(d.factionRank)&&!data.claimRenameOfficersAllowed)?3:2;
  return rank>=required;
 }
 public void onGuiClosed(){rememberLocation();awaitingSetup=false;if(validation.equals(loc("xf.setup.waiting")))validation="";super.onGuiClosed();}
 public boolean doesGuiPauseGame(){return false;}
 public boolean isSetupForm(){return !browsing&&selected!=null&&("c_create".equals(selected.id)||"c_claim".equals(selected.id)||"c_sethome".equals(selected.id));}
 public boolean isAction(String action){return !browsing&&selected!=null&&action.equals(selected.id);}
 private boolean setupPanel(){return isSetupForm()&&com.hfr.client.XFFactionSetup.visible();}
 private boolean compactSetup(){return setupPanel()&&left()<150;}
 private int fieldTop(){return compactSetup()?126:92;}
 public void setupResult(String message){awaitingSetup=false;validation=message;initGui();}
 private int panelWidth(){return Math.min(540,width-20);}
 private int left(){return (width-panelWidth())/2;}
 private void openInitialAction(){
  if(initialAction==null)return;
  for(XFCommandDefinition d:XFCommandCatalog.getCommands())if(d.id.equals(initialAction)){
   cat=d.category;search.setText("");page=0;selected=null;
   if(list().contains(d)){selected=d;browsing=false;argumentPage=0;}
   break;
  }
  initialAction=null;initGui();
 }
 public void initGui(){
  cachedCommand=null;allowUserInput=false;clampPage(list());buttonList.clear();
  int x=left(),w=panelWidth();
  if(search==null)search=new GuiTextField(fontRendererObj,x,71,w-80,18);
  search.xPosition=x;search.yPosition=71;search.width=w-80;
  buttonList.add(new GuiButtonExt(0,x,height-24,80,20,loc("xf.nav.back")));
  buttonList.add(new GuiButtonExt(5,x+w-80,height-24,80,20,loc("xf.home.help")));
  if(browsing){
   buttonList.add(new CategoryButton(12,x,22,"<"));
   buttonList.add(new CategoryButton(13,x+w-48,22,">"));
   buttonList.add(new GuiButtonExt(4,x+w-76,70,76,20,loc("xf.menu.refresh")));
   buttonList.add(new GuiButtonExt(10,x,pageControlsTop(),40,20,"<"));
   buttonList.add(new GuiButtonExt(11,x+w-40,pageControlsTop(),40,20,">"));
  }else{
   layoutFields();
   int bw=(w-8)/3;
   buttonList.add(new GuiButtonExt(1,x,height-49,bw,20,loc("xf.menu.run")));
   buttonList.add(new GuiButtonExt(2,x+bw+4,height-49,bw,20,loc("xf.menu.chat")));
   buttonList.add(new GuiButtonExt(3,x+(bw+4)*2,height-49,bw,20,loc("xf.menu.copy")));
   buttonList.add(new GuiButtonExt(7,x+84,height-24,w-168,20,loc("xf.menu.details")));
   if(selected!=null&&selected.arguments.length>argumentRows()){
    buttonList.add(new GuiButtonExt(10,x,height-75,30,20,"<"));
    buttonList.add(new GuiButtonExt(11,x+w-30,height-75,30,20,">"));
   }
  }
  for(Object o:buttonList){GuiButton b=(GuiButton)o;if(b.id!=0&&b.id!=5)b.enabled=ready&&!awaitingSetup;}
  updateNavigation(list());
 }
 /** Keep Forge's button surface and hit box; only double the category arrow glyph. */
 @SideOnly(Side.CLIENT) private static final class CategoryButton extends GuiButtonExt {
  CategoryButton(int id,int x,int y,String arrow){super(id,x,y,48,40,arrow);}
  public void drawCenteredString(FontRenderer font,String text,int x,int y,int color){
   GL11.glPushMatrix();
   try{GL11.glTranslatef(x,y-4,0);GL11.glScalef(2,2,1);super.drawCenteredString(font,text,0,0,color);}
   finally{GL11.glPopMatrix();}
  }
 }
 private void layoutFields(){
  if(selected==null)return;
  Map<String,GuiTextField> previous=new HashMap<String,GuiTextField>(fields);fields.clear();
  for(int i=0;i<selected.arguments.length;i++){
   GuiTextField f=new GuiTextField(fontRendererObj,left(),92,panelWidth()-28,18);
   f.setMaxStringLength(selected.arguments[i].preserveSpaces?256:64);
   if(previous.containsKey("a"+i))f.setText(previous.get("a"+i).getText());
   else if(selected.arguments[i].options.length==1)f.setText(selected.arguments[i].options[0]);
   fields.put("a"+i,f);
   if(!choices(selected.arguments[i]).isEmpty())buttonList.add(new GuiButtonExt(2000+i,left()+panelWidth()-24,92,24,18,">"));
  }
  positionFields();
 }
 protected void actionPerformed(GuiButton b){
  if(!b.enabled||!b.visible)return;
  cachedCommand=null;
  if(b.id==0){if(!browsing){browsing=true;initGui();}else mc.displayGuiScreen(parent);return;}
  if(b.id==5){mc.displayGuiScreen(new GUIXFHelp(this));return;}
  if(!ready||!currentSession()||awaitingSetup)return;
  if(b.id>=2000){int i=b.id-2000;List<String> options=choices(selected.arguments[i]);GuiTextField f=fields.get("a"+i);f.setText(options.get((options.indexOf(f.getText())+1)%options.size()));return;}
  if(b.id==12||b.id==13){changeCategory(b.id==12?-1:1);return;}
  if(b.id==10||b.id==11){changePage(b.id==10?-1:1);return;}
  if(b.id==4){requestData();return;}
  if(selected==null)return;
  if(b.id==7){showReference();return;}
  if(b.id==3){setClipboardString(usage(selected));return;}
  if(!validate())return;
  if(b.id==2){mc.displayGuiScreen(new GuiChat(command()));return;}
  if(b.id==1){if(selected.dangerous)mc.displayGuiScreen(new GuiYesNo(this,loc("xf.confirm.title"),command(),9));else send(command());}
 }
 public void confirmClicked(boolean ok,int id){if(ok&&id==9&&ready&&currentSession())send(command());else mc.displayGuiScreen(this);}
 private void send(String cmd){if(!ready||!currentSession()||awaitingSetup)return;rememberLocation();
  boolean wait=selected!=null&&com.hfr.client.XFFactionSetup.waitForResult(selected.id);
  if(wait){awaitingSetup=true;validation=loc("xf.setup.waiting");initGui();}
  if(mc.thePlayer!=null)mc.thePlayer.sendChatMessage(cmd);
  if(!wait)mc.displayGuiScreen(null);
 }
 private void showReference(){
  final GUIXenofactionsMenu menu=this;
  final String text=loc(selected.descriptionKey)+"\n\n"+loc("xf.menu.usage")+": "+usage(selected)+"\n\n"+loc("xf.menu.aliases")+": "+Arrays.toString(selected.aliases)+"\n\n"+loc("xf.menu.preview")+": "+command();
  mc.displayGuiScreen(new GuiScreen(){
   private int page;
   private int capacity(){return Math.max(1,(height-70)/12);}
   private List<String> lines(){return fontRendererObj.listFormattedStringToWidth(text,width-24);}
   public boolean doesGuiPauseGame(){return false;}
   public void initGui(){buttonList.clear();buttonList.add(new GuiButtonExt(0,width/2-40,height-25,80,20,loc("xf.nav.back")));buttonList.add(new GuiButtonExt(1,12,height-25,60,20,loc("xf.tutorial.back")));buttonList.add(new GuiButtonExt(2,width-72,height-25,60,20,loc("xf.tutorial.next")));}
   protected void actionPerformed(GuiButton b){if(b.id==0)mc.displayGuiScreen(menu);else page=Math.max(0,Math.min((lines().size()-1)/capacity(),page+(b.id==1?-1:1)));}
   protected void keyTyped(char c,int k){if(k==1)mc.displayGuiScreen(menu);}
   public void drawScreen(int mx,int my,float f){drawDefaultBackground();List<String> l=lines();page=Math.min(page,Math.max(0,(l.size()-1)/capacity()));drawCenteredString(fontRendererObj,loc("xf.menu.details"),width/2,12,0xffffff);for(int i=page*capacity(),y=34;i<Math.min(l.size(),(page+1)*capacity());i++,y+=12)drawString(fontRendererObj,l.get(i),12,y,0xdddddd);super.drawScreen(mx,my,f);}
  });
 } private boolean validate(){validation=""; if(command().length()>100){validation=loc("xf.validation.length");return false;} for(int i=0;i<selected.arguments.length;i++){XFCommandArgument a=selected.arguments[i]; String v=val(i); if(a.required&&v.trim().length()==0){argumentPage=i/argumentRows();positionFields();validation=loc("xf.validation.required");return false;} for(int j=0;j<v.length();j++){char c=v.charAt(j); if(c=='\n'||c=='\r'||Character.isISOControl(c)){validation=loc("xf.validation.control");return false;}} if(!a.preserveSpaces&&v.trim().contains(" ")&&!(a.type==Type.FACTION_TOKEN||a.type==Type.FACTION||a.type==Type.ALLY||a.type==Type.ENEMY||a.type==Type.CITY)){validation=loc("xf.validation.token");return false;} if(a.options.length>0&&v.trim().length()>0&&!Arrays.asList(a.options).contains(v.trim())){validation=loc("xf.validation.choice");return false;} if(a.type==Type.INTEGER&&v.trim().length()>0)try{Integer.parseInt(v.trim());}catch(Exception e){validation=loc("xf.validation.integer");return false;} if((a.type==Type.DECIMAL)&&v.trim().length()>0)try{Double.parseDouble(v.trim());}catch(Exception e){validation=loc("xf.validation.decimal");return false;} } for(int i=0;i<selected.arguments.length;i++){if(val(i).trim().isEmpty())for(int j=i+1;j<selected.arguments.length;j++)if(!val(j).trim().isEmpty()){validation=loc("xf.validation.position");return false;}} return true;}
 private String command(){if(cachedCommand!=null)return cachedCommand;List<String> vals=new ArrayList<String>(); if(selected!=null)for(int i=0;i<selected.arguments.length;i++){XFCommandArgument a=selected.arguments[i];vals.add(val(i));} return cachedCommand=selected==null?"":selected.buildCommand(vals);} private String val(int i){GuiTextField f=fields.get("a"+i);return f==null?"":f.getText();}
 public void updateScreen(){if(!currentSession()){mc.displayGuiScreen(null);return;}search.updateCursorCounter();for(GuiTextField f:fields.values())f.updateCursorCounter();}
 protected void mouseClicked(int mx,int my,int btn){
  keyboardButton=-1;super.mouseClicked(mx,my,btn);if(!ready||mc.currentScreen!=this)return;
  if(browsing){search.mouseClicked(mx,my,btn);List<XFCommandDefinition> l=list();clampPage(l);
   if(btn==0&&inside(mx,my,left(),listTop(),panelWidth(),listRows()*22)&&(my-listTop())%22<20){int i=page*listRows()+(my-listTop())/22;if(i<l.size()){selected=l.get(i);browsing=false;argumentPage=0;fields.clear();validation="";initGui();}}}
  else for(int i=0;selected!=null&&i<selected.arguments.length;i++)if(argumentVisible(i))fields.get("a"+i).mouseClicked(mx,my,btn);
 }
 protected void keyTyped(char c,int k){
  cachedCommand=null;if(k==1){if(isSetupForm()){mc.displayGuiScreen(null);return;}if(!browsing){browsing=true;initGui();}else mc.displayGuiScreen(parent);return;}if(!ready||!currentSession()||awaitingSetup)return;
  if(k==15){
   List<GuiTextField> inputs=new ArrayList<GuiTextField>();
   if(browsing)inputs.add(search);else for(int i=0;selected!=null&&i<selected.arguments.length;i++)if(argumentVisible(i))inputs.add(fields.get("a"+i));
   List<GuiButton> buttons=new ArrayList<GuiButton>();for(Object o:buttonList){GuiButton b=(GuiButton)o;if(b.visible&&b.enabled)buttons.add(b);}
   int at=-1;for(int i=0;i<inputs.size();i++)if(inputs.get(i).isFocused())at=i;
   for(int i=0;i<buttons.size();i++)if(buttons.get(i).id==keyboardButton)at=inputs.size()+i;
   if(at<0&&isShiftKeyDown())at=0;
   int next=(at+(isShiftKeyDown()?-1:1)+inputs.size()+buttons.size())%(inputs.size()+buttons.size());
   for(int i=0;i<inputs.size();i++)inputs.get(i).setFocused(i==next);
   keyboardButton=next>=inputs.size()?buttons.get(next-inputs.size()).id:-1;return;
  }
  if(k==28||k==156){
   int id=keyboardButton>=0?keyboardButton:!browsing?1:-1;
   for(Object o:buttonList){GuiButton b=(GuiButton)o;if(b.id==id&&b.visible&&b.enabled){actionPerformed(b);return;}}
  }
  if(browsing&&search.isFocused()){search.textboxKeyTyped(c,k);updateNavigation(list());}
  else for(int i=0;selected!=null&&i<selected.arguments.length;i++)if(argumentVisible(i))fields.get("a"+i).textboxKeyTyped(c,k);
 }
 public void handleMouseInput(){
  // GuiScreen handles clicks/drags only; read this event's wheel once, in GUI coordinates.
  super.handleMouseInput();int wheel=Mouse.getEventDWheel();
  if(wheel==0||mc.currentScreen!=this||!ready||!currentSession()||awaitingSetup)return;
  int mx=Mouse.getEventX()*width/mc.displayWidth;
  int my=height-Mouse.getEventY()*height/mc.displayHeight-1;
  int direction=wheel<0?1:-1;
  if(browsing){
   if(inside(mx,my,left(),22,panelWidth(),40)){changeCategory(direction);return;}
   if(inside(mx,my,left(),listTop(),panelWidth(),listRows()*22)
     ||inside(mx,my,left(),pageControlsTop(),panelWidth(),20)){changePage(direction);return;}
  }else if(selected!=null){
   // Argument choices are cycle buttons, not a suggestion popup. The form owns this region.
   if(inside(mx,my,left(),fieldTop()-10,panelWidth(),argumentRows()*34)
     ||inside(mx,my,left(),height-75,panelWidth(),20)){changePage(direction);return;}
  }
 }
 public void drawScreen(int mx,int my,float f){
  drawDefaultBackground();int x=left(),w=panelWidth();
  drawCenteredString(fontRendererObj,loc("xf.menu.title"),width/2,compactSetup()?72:8,0xffffff);
  if(!ready){drawCenteredString(fontRendererObj,loc("xf.menu.loading"),width/2,80,0xcccccc);super.drawScreen(mx,my,f);return;}
  if(browsing){
   drawCenteredString(fontRendererObj,fontRendererObj.trimStringToWidth(StatCollector.translateToLocalFormatted("xf.menu.category",loc("xf.category."+cat.name().toLowerCase(Locale.ROOT))),w-104),width/2,38,0xffdd88);
   search.drawTextBox();
   if(search.getText().isEmpty()&&!search.isFocused())drawString(fontRendererObj,loc("xf.menu.search"),x+4,76,0x888888);
   List<XFCommandDefinition> l=list();updateNavigation(l);int start=page*listRows();
   for(int i=start,y=listTop();i<Math.min(l.size(),start+listRows());i++,y+=22){XFCommandDefinition d=l.get(i);drawRect(x,y,x+w,y+20,d==selected?0x805050a0:0x80000000);drawString(fontRendererObj,fontRendererObj.trimStringToWidth(loc(d.displayKey),w-12),x+6,y+6,d.dangerous?0xffaaaa:0xffffff);}
   if(l.isEmpty())drawCenteredString(fontRendererObj,loc("xf.menu.empty"),width/2,listTop()+6,0xaaaaaa);
   drawCenteredString(fontRendererObj,StatCollector.translateToLocalFormatted("xf.menu.page",page+1,pageCount(l)),width/2,pageControlsTop()+6,0xaaaaaa);
  }else if(selected!=null){
   drawString(fontRendererObj,fontRendererObj.trimStringToWidth(loc(selected.displayKey),w),x,compactSetup()?88:28,0xffdd88);
   List<String> desc=fontRendererObj.listFormattedStringToWidth(loc(selected.descriptionKey),w);
   for(int i=0;!compactSetup()&&i<Math.min(2,desc.size());i++)drawString(fontRendererObj,desc.get(i),x,42+i*11,0xdddddd);
   String permission=selected.opLevel>0?StatCollector.translateToLocalFormatted("xf.permission.level",selected.opLevel):selected.factionRank==null?loc("xf.permission.none"):loc("xf.permission."+("ClaimRename".equals(selected.factionRank)?(data.claimRenameOfficersAllowed?"officer":"leader"):selected.factionRank.toLowerCase(Locale.ROOT)));
   drawString(fontRendererObj,fontRendererObj.trimStringToWidth(loc("xf.menu.requirements")+": "+permission,w),x,compactSetup()?106:66,0xaaaaaa);
   for(int i=argumentPage*argumentRows();i<Math.min(selected.arguments.length,(argumentPage+1)*argumentRows());i++){GuiTextField gf=fields.get("a"+i);drawString(fontRendererObj,fontRendererObj.trimStringToWidth(loc(selected.arguments[i].nameKey),w),x,gf.yPosition-10,0xcccccc);gf.drawTextBox();}
   if(selected.arguments.length>argumentRows())drawCenteredString(fontRendererObj,StatCollector.translateToLocalFormatted("xf.menu.arguments_page",argumentPage+1,(selected.arguments.length+argumentRows()-1)/argumentRows()),width/2,height-70,0xaaaaaa);
   if(!validation.isEmpty()){List<String> status=fontRendererObj.listFormattedStringToWidth(validation,w);for(int i=0;i<Math.min(3,status.size());i++)drawString(fontRendererObj,status.get(i),x,height-91+i*9,0xff8888);}
  }
  super.drawScreen(mx,my,f);
  if(setupPanel())com.hfr.client.XFFactionSetup.draw(width,height,true,left());
  for(Object o:buttonList){GuiButton b=(GuiButton)o;if(b.id==keyboardButton&&b.enabled&&b.visible){drawRect(b.xPosition,b.yPosition,b.xPosition+b.width,b.yPosition+1,0xffffdd88);drawRect(b.xPosition,b.yPosition+b.height-1,b.xPosition+b.width,b.yPosition+b.height,0xffffdd88);}}
  drawNavigationTooltip(mx,my);
 }
 private List<XFCommandDefinition> list(){if(!ready)return Collections.emptyList();String query=search==null?"":search.getText();
 int generation=com.hfr.client.render.GpuModelManager.generation();
 if(filteredData==data&&filteredCategory==cat&&generation==filteredGeneration&&query.equals(filteredQuery))return filtered;
 if(filteredGeneration!=generation)searchText.clear();
 filteredData=data;filteredCategory=cat;filteredGeneration=generation;filteredQuery=query;
 String q=query.toLowerCase(Locale.ROOT);List<XFCommandDefinition> r=new ArrayList<XFCommandDefinition>(); for(XFCommandDefinition d:XFCommandCatalog.getCommands()){ if(q.length()==0&&d.category!=cat)continue; if(d.opLevel>0&&!data.permittedCommands.contains(d.id))continue; if("tdm".equals(d.configRequirement)&&!data.tdmEnabled)continue; if("customflags".equals(d.configRequirement)&&!data.customFlagsEnabled)continue; if("xflags".equals(d.configRequirement)&&!data.xflagsEnabled)continue; String hay=searchText.get(d); if(hay==null){hay=(loc(d.displayKey)+" "+loc(d.descriptionKey)+" "+usage(d)+" "+Arrays.toString(d.aliases)+" /"+d.root+(d.sub==null?"":" "+d.sub)).toLowerCase(Locale.ROOT);searchText.put(d,hay);} if(q.length()==0||hay.contains(q))r.add(d);} filtered=Collections.unmodifiableList(r);return filtered;} private static String usage(XFCommandDefinition d){return StatCollector.canTranslate(d.usage)?loc(d.usage):d.usage;} private static String loc(String k){String s=StatCollector.translateToLocal(k);return s==null?k:s;}
 private int listTop(){return 98;}
 private int pageControlsTop(){return height-49;}
 private int listRows(){return Math.max(1,(pageControlsTop()-listTop()-4)/22);}
 private int pageCount(List<XFCommandDefinition> actions){return Math.max(1,(actions.size()+listRows()-1)/listRows());}
 private void clampPage(List<XFCommandDefinition> actions){page=Math.max(0,Math.min(pageCount(actions)-1,page));}
 private int argumentPages(){return selected==null?1:Math.max(1,(selected.arguments.length+argumentRows()-1)/argumentRows());}
 private static boolean inside(int mx,int my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
 private void updateNavigation(List<XFCommandDefinition> actions){
  clampPage(actions);
  argumentPage=Math.max(0,Math.min(argumentPages()-1,argumentPage));
  for(Object o:buttonList){GuiButton b=(GuiButton)o;boolean available=ready&&!awaitingSetup;
   if(b.id==12)b.enabled=available&&cat.ordinal()>0;
   if(b.id==13)b.enabled=available&&cat.ordinal()<XFCommandDefinition.Category.values().length-1;
   if(b.id==10)b.enabled=available&&(browsing?page:argumentPage)>0;
   if(b.id==11)b.enabled=available&&(browsing?page<pageCount(actions)-1:argumentPage<argumentPages()-1);
  }
 }
 private void changeCategory(int direction){
  XFCommandDefinition.Category[] all=XFCommandDefinition.Category.values();
  int next=Math.max(0,Math.min(all.length-1,cat.ordinal()+direction));
  if(next==cat.ordinal())return;
  rememberLocation();fields.clear();restoreLocation(all[next]);
  selected=null;browsing=true;argumentPage=0;initGui();rememberLocation();
 }
 private void changePage(int direction){
  List<XFCommandDefinition> actions=list();clampPage(actions);
  if(browsing)page=Math.max(0,Math.min(pageCount(actions)-1,page+direction));
  else if(selected!=null){argumentPage=Math.max(0,Math.min(argumentPages()-1,argumentPage+direction));positionFields();}
  updateNavigation(actions);rememberLocation();
 }
 private void drawNavigationTooltip(int mx,int my){
  for(Object o:buttonList){GuiButton b=(GuiButton)o;
   if(!b.visible||!inside(mx,my,b.xPosition,b.yPosition,b.width,b.height))continue;
   String key=b.id==12?"xf.menu.previous_category":b.id==13?"xf.menu.next_category":
     b.id==10?(browsing?"xf.menu.previous_page":"xf.menu.previous_arguments"):
     b.id==11?(browsing?"xf.menu.next_page":"xf.menu.next_arguments"):null;
   if(key!=null)drawHoveringText(fontRendererObj.listFormattedStringToWidth(loc(key),Math.min(220,width-24)),mx,my,fontRendererObj);
   return;
  }
 }
 private int argumentRows(){return Math.max(1,(height-fieldTop()-80)/34);}
 private boolean argumentVisible(int i){return i>=argumentPage*argumentRows()&&i<(argumentPage+1)*argumentRows();}
 private void positionFields(){
  if(selected==null)return;
  argumentPage=Math.min(argumentPage,Math.max(0,(selected.arguments.length-1)/argumentRows()));
  for(int i=0;i<selected.arguments.length;i++){GuiTextField field=fields.get("a"+i);field.yPosition=fieldTop()+(i-argumentPage*argumentRows())*34;if(!argumentVisible(i))field.setFocused(false);}
  for(Object o:buttonList){GuiButton b=(GuiButton)o;if(b.id>=2000){int i=b.id-2000;b.visible=argumentVisible(i);b.yPosition=fields.get("a"+i).yPosition;}}
  updateNavigation(list());
 } private List<String> choices(XFCommandArgument a){
  List<String> result=new ArrayList<String>(); if(!a.required)result.add("");
  if(a.options.length>0){for(String option:a.options)if(!result.contains(option))result.add(option);return result;}
  Collection<String> source=Collections.emptyList();
  switch(a.type){
   case ONLINE_PLAYER: source=data.onlinePlayers;break;
   case FACTION: source=data.factions;break;
   case FACTION_MEMBER: source=data.members;break;
   case APPLICANT: source=data.applicants;break;
   case ALLY: source=data.allies;break;
   case ENEMY: case FACTION_TOKEN: source=data.enemies;break;
   case WARP: source=data.warps;break;
   case CITY: source=data.cities;break;
   case FLAG: source=data.flags;break;
   default: return Collections.emptyList();
  }
  for(String value:source)if(!result.contains(value))result.add(value);return result;
 }
}

