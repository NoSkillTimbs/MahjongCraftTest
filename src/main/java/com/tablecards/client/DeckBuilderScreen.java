package com.tablecards.client;

import com.tablecards.engine.Json;
import com.tablecards.engine.JsonWriter;
import com.tablecards.net.DeckBuilderPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;

/** Paged catalog and deck rows: at most a screenful of widgets, even for large imported libraries. */
public final class DeckBuilderScreen extends Screen {
    private final Screen parent;
    private final DeckBuilderModel model = new DeckBuilderModel();
    private TextFieldWidget search, name;
    private String query="", deckName="My deck", status="Loading server card catalog...";
    private String pendingLoad;
    private List<Object> pendingRows;
    private DeckBuilderModel.Entry preview;
    private boolean side, confirmDiscard, confirmPartial;
    private int request, browseRequest, loadRequest, deckPage, debounce=-1;
    private int catalogX, deckX, previewX, colWidth, visibleRows;
    private static int nextRequest;

    public DeckBuilderScreen(Screen parent) { super(Text.literal("Deck builder")); this.parent=parent; }
    @Override protected void init() { rebuild(); browse(); }
    private void button(String text,int x,int y,int w,Runnable action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(text),b -> action.run()).dimensions(x,y,w,18).build());
    }
    private void rebuild() {
        clearChildren();
        catalogX=8; colWidth=Math.max(70,(width-32)/3); deckX=catalogX+colWidth+8; previewX=deckX+colWidth+8;
        visibleRows=Math.max(1,Math.min(12,(height-153)/20));
        button(model.game.equals("ygo")?"Yu-Gi-Oh!":"Pokemon",8,22,98,() -> {
            if (!discardAllowed()) return;
            model.game=model.game.equals("ygo")?"ptcg":"ygo";
            model.deck.clear(); model.catalog.clear(); model.dirty=false; model.page=deckPage=0;
            preview=null; side=false; pendingRows=null; confirmPartial=false; status="Loading..."; rebuild(); browse();
        });
        button("Back",width-56,22,48,this::close);
        search=new TextFieldWidget(textRenderer,112,22,Math.max(40,width-176),18,Text.literal("Search card names, types, attributes or text"));
        search.setMaxLength(100); search.setText(query);
        search.setChangedListener(value -> { query=value; model.page=0; debounce=6; }); addDrawableChild(search);
        name=new TextFieldWidget(textRenderer,8,47,Math.max(55,width/3),18,Text.literal("Deck name"));
        name.setMaxLength(64); name.setText(deckName); name.setChangedListener(value -> deckName=value); addDrawableChild(name);
        int bx=width/3+16;
        button("Save",bx,47,44,this::save); button("Load",bx+48,47,44,this::load);
        button("Files >",bx+96,47,52,this::nextFile);
        button("Use",bx+152,47,40,this::use);
        if(model.game.equals("ygo")) button(side?"Add: Side":"Add: Main/Extra",deckX,72,colWidth,() -> {side=!side; rebuild();});
        else button("New / clear",deckX,72,colWidth,() -> {if(discardAllowed()){model.deck.clear();model.dirty=false;deckPage=0;rebuild();}});
        int y=96;
        // Catalog page size is 12; on small screens use wheel to inspect the remaining rows.
        int first=catalogScroll;
        for(int i=first;i<Math.min(model.catalog.size(),first+visibleRows);i++) {
            DeckBuilderModel.Entry e=model.catalog.get(i);
            button("+ "+shorten(e.card().name),catalogX,y,colWidth,() -> {
                preview=e;
                try {model.add(e,side);status="Added "+e.card().name;confirmDiscard=false;}
                catch(RuntimeException ex){status=ex.getMessage();}
                rebuild();
            }); y+=20;
        }
        int from=deckPage*visibleRows;
        if(from>=model.deck.size() && deckPage>0){deckPage=Math.max(0,(model.deck.size()-1)/visibleRows);from=deckPage*visibleRows;}
        y=96;
        for(int i=from;i<Math.min(model.deck.size(),from+visibleRows);i++) {
            int index=i; var e=model.deck.get(i);
            button("- ["+e.section().charAt(0)+"] "+shorten(e.card().name),deckX,y,colWidth,() -> {
                preview=e;model.deck.remove(index);model.dirty=true;confirmDiscard=false;rebuild();
            }); y+=20;
        }
        int bottom=height-48;
        button("<",catalogX,bottom,22,() -> {if(model.page>0){model.page--;catalogScroll=0;browse();}});
        button(">",catalogX+colWidth-22,bottom,22,() -> {if((model.page+1)*12<model.total){model.page++;catalogScroll=0;browse();}});
        button("<",deckX,bottom,22,() -> {deckPage=Math.max(0,deckPage-1);rebuild();});
        button(">",deckX+colWidth-22,bottom,22,() -> {if((deckPage+1)*visibleRows<model.deck.size())deckPage++;rebuild();});
    }
    private int catalogScroll;
    private String shorten(String text){return textRenderer.trimToWidth(text,Math.max(20,colWidth-35));}
    private boolean discardAllowed(){
        if(!model.dirty || confirmDiscard){confirmDiscard=false;return true;}
        confirmDiscard=true;status="Unsaved changes. Repeat the action to discard, or Save.";return false;
    }
    private int send(String op,Map<String,Object> fields){
        var req=new LinkedHashMap<String,Object>(fields);
        request=++nextRequest;req.put("request",request);req.put("game",model.game);req.put("op",op);
        ClientPlayNetworking.send(new DeckBuilderPayload(JsonWriter.write(req)));return request;
    }
    private void browse(){browseRequest=send("browse",Map.of("query",query,"page",model.page));}
    private java.nio.file.Path config(){return FabricLoader.getInstance().getConfigDir();}
    private void save(){
        try {DeckFiles.save(config(),deckName,model.game,model.serialize());model.dirty=false;status="Saved "+deckName+(model.game.equals("ygo")?".ydk":".txt")+" locally (Use validates legality).";}
        catch(Exception e){status=e.getMessage();}
    }
    private void load(){
        if(confirmPartial && pendingRows!=null){
            model.readDeck(pendingRows);model.dirty=true;pendingRows=null;confirmPartial=false;
            deckName = pendingLoad.substring(0,Math.min(52,pendingLoad.length())) + " recovered";
            status="Recovered supported cards under a new name; the original file is unchanged.";rebuild();return;
        }
        if(!discardAllowed())return;
        try {pendingLoad=deckName;loadRequest=send("load",Map.of("text",DeckFiles.load(config(),deckName,model.game)));status="Validating local file against server cards...";}
        catch(Exception e){status=e.getMessage();}
    }
    private void nextFile(){
        try {var files=DeckFiles.names(config(),model.game);if(files.isEmpty()){status="No local deck files for this game.";return;}
            deckName=files.get((files.indexOf(deckName)+1)%files.size());name.setText(deckName);status="Selected "+deckName+"; press Load to open it.";}
        catch(Exception e){status=e.getMessage();}
    }
    private void use(){
        try {loadRequest=send("use",Map.of("text",model.serialize(),"name",deckName));status="Checking deck with server...";}
        catch(Exception e){status=e.getMessage();}
    }
    public void receive(String json){
        Map<String,Object> reply=Json.obj(Json.parse(json));
        if(!model.game.equals(Json.str(reply,"game","")))return;
        int id=Json.num(reply,"request",-1);String op=Json.str(reply,"op","");
        if(op.equals("browse") && id==browseRequest){
            model.catalog.clear();for(Object row:Json.arr(reply.get("entries")))model.catalog.add(DeckBuilderModel.Entry.read(Json.obj(row)));
            model.total=Json.num(reply,"total",0);catalogScroll=0;
            if(preview==null && !model.catalog.isEmpty())preview=model.catalog.get(0);
            var problems=Json.arr(reply.get("problems"));
            status=!problems.isEmpty()?problems.toString():model.total==0?"No matching cards. Try a broader search; the server must import card data first.":model.total+" supported cards. Hover to preview; + adds, - removes.";rebuild();
        } else if(id==loadRequest){
            List<Object> problems=Json.arr(reply.get("problems"));
            if(op.equals("use")){
                status=Boolean.TRUE.equals(reply.get("accepted"))?"Deck accepted for this table.":problems.toString();
                // Keep the editor open so an unsaved deck is never silently lost.
            }else if(op.equals("load")){
                if(!problems.isEmpty()){
                    pendingRows=Json.arr(reply.get("entries"));confirmPartial=true;
                    status=problems+". Press Load again to edit the supported cards.";
                }else{
                    model.readDeck(Json.arr(reply.get("entries")));deckName=pendingLoad;deckPage=0;status="Loaded "+deckName;rebuild();
                }
            }
        }
    }
    @Override public void tick(){if(debounce>=0 && --debounce==0){debounce=-1;browse();}}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(x<deckX){catalogScroll=Math.max(0,Math.min(Math.max(0,model.catalog.size()-visibleRows),catalogScroll-(int)Math.signum(vertical)));rebuild();return true;}
        if(x<previewX){deckPage=Math.max(0,Math.min(Math.max(0,(model.deck.size()-1)/visibleRows),deckPage-(int)Math.signum(vertical)));rebuild();return true;}
        detailScroll=Math.max(0,detailScroll-(int)Math.signum(vertical)*3);return true;
    }
    private int detailScroll;
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        ctx.fill(0,0,width,height,0xFF101722);
        ctx.drawTextWithShadow(textRenderer,"Deck builder — local files / server card pool",8,7,0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer,"Cards: "+model.total,catalogX,77,0xFFB8C9DD);
        // Preview follows hover without adding/removing a card.
        if(my>=96 && my<96+visibleRows*20){
            int row=(my-96)/20;
            if(mx>=catalogX && mx<catalogX+colWidth && row+catalogScroll<model.catalog.size())setPreview(model.catalog.get(row+catalogScroll));
            if(mx>=deckX && mx<deckX+colWidth && row+deckPage*visibleRows<model.deck.size())setPreview(model.deck.get(row+deckPage*visibleRows));
        }
        if(preview!=null){
            int h=Math.max(30,Math.min(height/3,150)),w=(int)(h*.7),x=previewX+(colWidth-w)/2;
            CardUi.draw(new McCanvas(ctx,textRenderer),preview.card(),x,76,w,h,ClientSettings.cardArt());
            int y=82+h;
            var lines=new ArrayList<net.minecraft.text.OrderedText>();
            lines.addAll(textRenderer.wrapLines(Text.literal(preview.card().name),colWidth));
            for(String paragraph:preview.card().text)lines.addAll(textRenderer.wrapLines(Text.literal(paragraph),colWidth));
            detailScroll=Math.min(detailScroll,Math.max(0,lines.size()-1));
            ctx.enableScissor(previewX,y,Math.min(width,previewX+colWidth),height-52);
            for(int i=detailScroll;i<lines.size() && y<height-52;i++,y+=10)ctx.drawText(textRenderer,lines.get(i),previewX,y,0xFFE3E9F0,false);
            ctx.disableScissor();
        }
        super.render(ctx,mx,my,delta);
        ctx.drawTextWithShadow(textRenderer,"Page "+(model.page+1),catalogX+26,height-43,0xFFCCCCCC);
        long main=model.deck.stream().filter(e->e.section().equals("main")).count();
        long extra=model.deck.stream().filter(e->e.section().equals("extra")).count();
        String counts=model.game.equals("ygo")?main+" M / "+extra+" E / "+(model.deck.size()-main-extra)+" S":model.deck.size()+" cards";
        ctx.drawTextWithShadow(textRenderer,counts,deckX+26,height-43,0xFFCCCCCC);
        ctx.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(status,width-16),8,height-22,0xFFFFDA80);
        if (my >= height-26) {
            var lines=textRenderer.wrapLines(Text.literal(status),width-24);
            int top=Math.max(45,height-32-lines.size()*10);
            ctx.fill(6,top-4,width-6,height-28,0xF0101722);
            for (var line:lines) { if(top>=height-28)break; ctx.drawText(textRenderer,line,12,top,0xFFFFDA80,false);top+=10; }
        }
    }
    private void setPreview(DeckBuilderModel.Entry e){if(preview!=e){preview=e;detailScroll=0;}}
    @Override public boolean shouldPause(){return false;}
    @Override public void close(){if(discardAllowed()){
        // The parent may have missed live board updates while the editor was open.
        if(parent instanceof TableGameScreen) TableCardsClient.reopenGame();
        else client.setScreen(parent);
    }}
}
