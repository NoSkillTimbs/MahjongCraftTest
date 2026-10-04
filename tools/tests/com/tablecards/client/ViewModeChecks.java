package com.tablecards.client;

import com.tablecards.engine.*;
import com.tablecards.engine.ygo.*;
import java.util.*;

/** Exercises the actual button click path and the rectangles used by duel-view interactions. */
public final class ViewModeChecks {
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static final class Paint implements Canvas {
        final Map<String,float[]> text=new HashMap<>();
        public void fill(int a,int b,int c,int d,int color){}
        public void gradient(int a,int b,int c,int d,int top,int bottom){}
        public void text(String s,float x,float y,int argb,float scale,boolean shadow){text.put(s,new float[]{x,y});}
        public int textWidth(String s){return s.length()*6;}
        public void texture(String name,int x,int y,int w,int h){}
        public boolean art(String key,int x,int y,int w,int h,boolean large){return false;}
        public void push(){} public void pop(){} public void translate(float x,float y){} public void rotate90(){}
        public void scissor(int a,int b,int c,int d){} public void noScissor(){}
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        Map<String,Object> definitions=new LinkedHashMap<>();
        for(int i=1;i<=14;i++)definitions.put("m"+i,Map.of("name","Fixture "+i,"kind","monster","level",4,"atk",1000,"codes",List.of(""+i)));
        YgoLibrary library=YgoLibrary.parse(JsonWriter.write(Map.of("cards",definitions)));
        List<YgoCard> deck=new ArrayList<>();for(int i=0;i<40;i++)deck.add(library.cards.get("m"+(i%14+1)));
        YgoGame engine=new YgoGame(new String[]{"A","B"},deck,deck,5);
        final int[] calls={0};
        GameView.Actions actions=new GameView.Actions(){public void choose(int i){calls[0]++;} public void concede(){calls[0]++;} public void close(){}public void toggleArt(){}public boolean artEnabled(){return false;}};
        GameView.World world=new GameView.World(){public List<GameView.Hit> hits(){return List.of();}public void highlight(Set<Integer> ids,String hover,int selected){}};
        for(int seat=0;seat<2;seat++)for(int width:new int[]{320,480,854}){
            var data=new LinkedHashMap<String,Object>();data.put("seat",seat);data.put("seq",7);data.put("table",List.of(0,0,0));data.put("yourTurn",true);data.put("canConcede",true);
            ViewJson.write(engine,seat,engine.pending(),data);
            ViewModel model=ViewModel.parse(JsonWriter.write(data));GameView view=new GameView(model,actions,world);Paint p=new Paint();
            require(view.inWorld(),"Default view must preserve existing world presentation");view.render(p,width,480,-1,-1,0);
            float[] mode=p.text.get("View Mode: Default View");require(mode!=null && mode[0]>=0,"Mode control is visible even at minimum GUI width");
            float[] art=p.text.get("Card art: off"),hide=p.text.get("Hide");require(art[0]<mode[0] && mode[0]<hide[0],"Button order: card art, view mode, hide");
            require(view.click(mode[0]+1,mode[1]+1),"Mode button has a working hit region");
            require(!view.inWorld() && view.view()==model && model.seq==7 && calls[0]==0,"Toggling changes presentation only");
            p.text.clear();view.render(p,width,480,-1,-1,1);
            var f=GameView.class.getDeclaredField("fieldRects");f.setAccessible(true);Map<String,int[]> rects=(Map<String,int[]>)f.get(view);
            for(int slot=0;slot<5;slot++){
                int[] a=rects.get("0:monsters:"+slot),b=rects.get("1:monsters:"+(4-slot));
                require(a[0]==b[0],"Monster rows oppose one another");
                int[] s=rects.get("0:spells:"+slot),t=rects.get("1:spells:"+(4-slot));
                require(s[0]==t[0] && s[0]==a[0],"Spell/trap rows match monster columns");
            }
            mode=p.text.get("View Mode: Duel View");view.click(mode[0]+1,mode[1]+1);require(view.inWorld() && calls[0]==0,"Can return without a gameplay action");
        }
        System.out.println("PASS: view-mode clicks, state preservation and screen geometry for both seats at 3 GUI widths");
    }
}
