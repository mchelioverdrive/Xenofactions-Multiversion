package com.hfr.inventory.gui;
import cpw.mods.fml.client.config.GuiButtonExt; import java.util.*; import com.hfr.packet.PacketDispatcher; import com.hfr.packet.client.XFMenuDataRequestPacket; import com.hfr.xfgui.*; import com.hfr.xfgui.XFCommandArgument.Type; import net.minecraft.client.*; import net.minecraft.client.gui.*; import net.minecraft.util.*; import org.lwjgl.input.Mouse; import cpw.mods.fml.relauncher.*;
@SideOnly(Side.CLIENT) public class GUIXenofactionsMenu extends GuiScreen implements GuiYesNoCallback { private static XFMenuData data=new XFMenuData(); private static boolean pending; private static int nextRequest; private static int pendingRequest; private static long pendingSession; private final long session=XFMenuNavigation.session(); private boolean ready; private GuiTextField search; private final Map<String,GuiTextField> fields=new HashMap<String,GuiTextField>(); private XFCommandDefinition.Category cat=XFCommandDefinition.Category.FACTION; private XFCommandDefinition selected; private int scroll; private int argumentPage; private String validation="";
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
  if(ready&&currentSession())XFMenuNavigation.remember(cat,new XFMenuNavigation.Location(selected==null?null:selected.id,search==null?"":search.getText(),scroll,argumentPage));
 }
 private void restoreLocation(XFCommandDefinition.Category category){
  XFMenuNavigation.Location location=XFMenuNavigation.location(category);
  cat=category; search.setText(location.search); scroll=location.scroll; argumentPage=location.argumentPage;
  selected=null; validation="";
  for(XFCommandDefinition d:list())if(d.id.equals(location.commandId)&&canRestore(d)){selected=d;break;}
  if(selected==null)argumentPage=0;
  browsing=selected==null; clampScroll(); initGui(); rememberLocation();
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
   cat=d.category;search.setText("");scroll=0;selected=null;
   if(list().contains(d)){selected=d;browsing=false;argumentPage=0;}
   break;
  }
  initialAction=null;initGui();
 }
 public void initGui(){
  cachedCommand=null;clampScroll();buttonList.clear();
  int x=left(),w=panelWidth();
  if(search==null)search=new GuiTextField(fontRendererObj,x,51,w-80,18);
  search.xPosition=x;search.yPosition=51;search.width=w-80;
  buttonList.add(new GuiButtonExt(0,x,height-24,80,20,loc("xf.nav.back")));
  buttonList.add(new GuiButtonExt(5,x+w-80,height-24,80,20,loc("xf.home.help")));
  if(browsing){
   buttonList.add(new GuiButtonExt(12,x,26,24,20,"<"));
   buttonList.add(new GuiButtonExt(13,x+w-24,26,24,20,">"));
   buttonList.add(new GuiButtonExt(4,x+w-76,50,76,20,loc("xf.menu.refresh")));
   buttonList.add(new GuiButtonExt(10,x,height-49,40,18,"<"));
   buttonList.add(new GuiButtonExt(11,x+w-40,height-49,40,18,">"));
  }else{
   layoutFields();
   int bw=(w-8)/3;
   buttonList.add(new GuiButtonExt(1,x,height-49,bw,20,loc("xf.menu.run")));
   buttonList.add(new GuiButtonExt(2,x+bw+4,height-49,bw,20,loc("xf.menu.chat")));
   buttonList.add(new GuiButtonExt(3,x+(bw+4)*2,height-49,bw,20,loc("xf.menu.copy")));
   buttonList.add(new GuiButtonExt(7,x+84,height-24,w-168,20,loc("xf.menu.details")));
   if(selected!=null&&selected.arguments.length>argumentRows()){
    buttonList.add(new GuiButtonExt(10,x,height-75,30,18,"<"));
    buttonList.add(new GuiButtonExt(11,x+w-30,height-75,30,18,">"));
   }
  }
  for(Object o:buttonList){GuiButton b=(GuiButton)o;if(b.id!=0&&b.id!=5)b.enabled=ready&&!awaitingSetup;}
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
  cachedCommand=null;
  if(b.id==0){if(!browsing){browsing=true;initGui();}else mc.displayGuiScreen(parent);return;}
  if(b.id==5){mc.displayGuiScreen(new GUIXFHelp(this));return;}
  if(!ready||!currentSession()||awaitingSetup)return;
  if(b.id>=2000){int i=b.id-2000;List<String> options=choices(selected.arguments[i]);GuiTextField f=fields.get("a"+i);f.setText(options.get((options.indexOf(f.getText())+1)%options.size()));return;}
  if(b.id==12||b.id==13){rememberLocation();XFCommandDefinition.Category[] all=XFCommandDefinition.Category.values();fields.clear();restoreLocation(all[(cat.ordinal()+all.length+(b.id==12?-1:1))%all.length]);browsing=true;initGui();return;}
  if(b.id==10||b.id==11){
   if(browsing){scroll+=b.id==10?-listRows():listRows();clampScroll();}
   else{argumentPage=Math.max(0,Math.min((selected.arguments.length-1)/argumentRows(),argumentPage+(b.id==10?-1:1)));positionFields();}
   return;
  }
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
  if(browsing){search.mouseClicked(mx,my,btn);if(btn==0&&mx>=left()&&mx<left()+panelWidth()&&my>=80&&my<80+listRows()*22){int i=scroll+(my-80)/22;List<XFCommandDefinition> l=list();if(i<l.size()){selected=l.get(i);browsing=false;argumentPage=0;fields.clear();validation="";initGui();}}}
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
  if(browsing&&search.isFocused()){search.textboxKeyTyped(c,k);scroll=0;}
  else for(int i=0;selected!=null&&i<selected.arguments.length;i++)if(argumentVisible(i))fields.get("a"+i).textboxKeyTyped(c,k);
 }
 public void handleMouseInput(){super.handleMouseInput();int d=Mouse.getEventDWheel();if(d!=0&&ready&&browsing){scroll+=d<0?1:-1;clampScroll();}}
 public void drawScreen(int mx,int my,float f){
  drawDefaultBackground();int x=left(),w=panelWidth();
  drawCenteredString(fontRendererObj,loc("xf.menu.title"),width/2,compactSetup()?72:8,0xffffff);
  if(!ready){drawCenteredString(fontRendererObj,loc("xf.menu.loading"),width/2,80,0xcccccc);super.drawScreen(mx,my,f);return;}
  if(browsing){
   drawCenteredString(fontRendererObj,loc("xf.category."+cat.name().toLowerCase(Locale.ROOT)),width/2,32,0xffdd88);
   if(search.getText().isEmpty()&&!search.isFocused())drawString(fontRendererObj,loc("xf.menu.search"),x+4,56,0x888888);
   search.drawTextBox();List<XFCommandDefinition> l=list();
   for(int i=scroll,y=80;i<Math.min(l.size(),scroll+listRows());i++,y+=22){XFCommandDefinition d=l.get(i);drawRect(x,y,x+w,y+20,d==selected?0x805050a0:0x80000000);drawString(fontRendererObj,fontRendererObj.trimStringToWidth(loc(d.displayKey),w-12),x+6,y+6,d.dangerous?0xffaaaa:0xffffff);}
   drawCenteredString(fontRendererObj,l.isEmpty()?loc("xf.menu.empty"):(scroll/listRows()+1)+"/"+Math.max(1,(l.size()+listRows()-1)/listRows()),width/2,height-43,0xaaaaaa);
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
 }
 private List<XFCommandDefinition> list(){if(!ready)return Collections.emptyList();String query=search==null?"":search.getText();
 int generation=com.hfr.client.render.GpuModelManager.generation();
 if(filteredData==data&&filteredCategory==cat&&generation==filteredGeneration&&query.equals(filteredQuery))return filtered;
 if(filteredGeneration!=generation)searchText.clear();
 filteredData=data;filteredCategory=cat;filteredGeneration=generation;filteredQuery=query;
 String q=query.toLowerCase(Locale.ROOT);List<XFCommandDefinition> r=new ArrayList<XFCommandDefinition>(); for(XFCommandDefinition d:XFCommandCatalog.getCommands()){ if(q.length()==0&&d.category!=cat)continue; if(d.opLevel>0&&!data.permittedCommands.contains(d.id))continue; if("tdm".equals(d.configRequirement)&&!data.tdmEnabled)continue; if("customflags".equals(d.configRequirement)&&!data.customFlagsEnabled)continue; if("xflags".equals(d.configRequirement)&&!data.xflagsEnabled)continue; String hay=searchText.get(d); if(hay==null){hay=(loc(d.displayKey)+" "+loc(d.descriptionKey)+" "+usage(d)+" "+Arrays.toString(d.aliases)+" /"+d.root+(d.sub==null?"":" "+d.sub)).toLowerCase(Locale.ROOT);searchText.put(d,hay);} if(q.length()==0||hay.contains(q))r.add(d);} filtered=Collections.unmodifiableList(r);return filtered;} private static String usage(XFCommandDefinition d){return StatCollector.canTranslate(d.usage)?loc(d.usage):d.usage;} private static String loc(String k){String s=StatCollector.translateToLocal(k);return s==null?k:s;}
 private int listRows(){return Math.max(1,(height-135)/22);}
 private void clampScroll(){scroll=Math.max(0,Math.min(Math.max(0,list().size()-listRows()),scroll));}
 private int argumentRows(){return Math.max(1,(height-fieldTop()-80)/34);}
 private boolean argumentVisible(int i){return i>=argumentPage*argumentRows()&&i<(argumentPage+1)*argumentRows();}
 private void positionFields(){
  if(selected==null)return;
  argumentPage=Math.min(argumentPage,Math.max(0,(selected.arguments.length-1)/argumentRows()));
  for(int i=0;i<selected.arguments.length;i++){GuiTextField field=fields.get("a"+i);field.yPosition=fieldTop()+(i-argumentPage*argumentRows())*34;if(!argumentVisible(i))field.setFocused(false);}
  for(Object o:buttonList){GuiButton b=(GuiButton)o;if(b.id>=2000){int i=b.id-2000;b.visible=argumentVisible(i);b.yPosition=fields.get("a"+i).yPosition;}}
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

