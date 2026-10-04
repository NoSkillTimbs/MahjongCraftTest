package com.tablecards.engine.ygo;

import com.tablecards.engine.*;
import java.util.*;

public final class PresentationChecks {
    static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
    static List<YgoCard> deck(){
        List<YgoCard> cards=new ArrayList<>();
        for(int i=0;i<40;i++)cards.add(new YgoCard("fixture"+(i%14),Map.of("name","Fixture "+i%14,"kind","monster","level",4,"atk",1500,"def",1000,"codes",List.of(""+(i%14+1)))));
        return cards;
    }
    static YgoGame game(){return new YgoGame(new String[]{"A","B"},deck(),deck(),123);}
    public static void main(String[] args){
        YgoGame g=game();
        require(g.drainPresentation().isEmpty(),"No opening draw reveals");
        g.drawCard(0);require(g.drainPresentation().isEmpty(),"Normal draw is silent");
        require(!g.choose(-1,0),"Invalid client seat rejected");require(g.drainPresentation().isEmpty(),"Rejected action has no events");
        var c=g.p[0].hand.get(0);g.specialSummon(c,0,"special",false);
        var events=g.drainPresentation();require(events.size()==1,"Special summon emits exactly one reveal");
        require(events.get(0).get("source").equals(g.presentationId(c)),"Reveal identifies the actual instance");
        require(Json.str(Json.obj(events.get(0).get("card")),"name","").equals(c.def.name),"Correct card face");
        require(g.drainPresentation().isEmpty(),"Draining cannot replay");
        var t=g.p[1].hand.get(0);g.specialSummon(t,1,"special",false);g.drainPresentation();
        var def=new YgoCard("fixture-spell",Map.of("name","Fixture target spell","kind","spell","codes",List.of("777")));
        var source=new YgoGame.Card(def,0,900);g.p[0].hand.add(source);source.zone=YgoGame.Zone.HAND;
        var fx=Fx.activate("Target one monster").cost((game,self,link,done)->YgoScripts.target(game,link,"Target",List.of(t),done));
        g.activate(0,source,fx,null,()->{});
        require(g.drainPresentation().stream().anyMatch(e->e.get("kind").equals("reveal")),"Spell activation reveal");
        require(g.choose(0,0),"Accept actual target choice");
        int sourceToken=g.presentationId(source), targetToken=g.presentationId(t);
        require(g.drainPresentation().stream().anyMatch(e->e.get("kind").equals("target") && e.get("source").equals(sourceToken) && e.get("target").equals(targetToken)),"White line uses real chain target");
        // Separate normal summons, sets, combat and state serialization from presentation mechanics.
        boolean summon=false,set=false,combat=false;
        for(int run=0;run<4;run++){
            g=new YgoGame(new String[]{"A","B"},deck(),deck(),run);
            for(int step=0;step<500 && !g.isOver();step++){
                Decision d=g.pending();int option=g.bot().choose(g,d);
                if(!set)for(int i=0;i<d.options().size();i++)if(d.options().get(i).move().type().equals("set_monster")){option=i;break;}
                String type=d.options().get(option).move().type();
                g.choose(d.player(),option);events=g.drainPresentation();
                if(type.equals("set_monster")){set=true;require(events.stream().noneMatch(e->e.get("kind").equals("reveal")),"Setting hidden cards must not reveal them");}
                if(type.equals("summon")){summon=true;require(events.stream().anyMatch(e->e.get("kind").equals("reveal")),"Normal summon reveals");}
                if(events.stream().anyMatch(e->e.get("kind").equals("attack")))combat=true;
                Map<String,Object> view=new LinkedHashMap<>();ViewJson.write(g,-1,null,view);ViewJson.write(g,0,g.pending(),new LinkedHashMap<>());
                require(g.drainPresentation().isEmpty(),"Serializing spectator/player snapshots never emits effects");
            }
        }
        require(set && summon && combat,"Fixture games cover set, summon and red combat events");
        System.out.println("PASS: Yu-Gi-Oh! semantic reveal/target/combat/hidden-info checks");
    }
}
