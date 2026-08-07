package com.rpgos.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RelationGraph(edges:List<RelationEdge>, modifier:Modifier=Modifier){
    val nodes=(edges.flatMap{listOf(it.source,it.target)}).distinct().take(24)
    Box(modifier){
        Canvas(Modifier.fillMaxSize()){
            if(nodes.isEmpty()) return@Canvas
            val center=Offset(size.width/2,size.height/2)
            val radius=minOf(size.width,size.height)*0.38f
            val pos=nodes.mapIndexed{i,n->
                val a=(Math.PI*2*i/nodes.size).toFloat()
                n to Offset(center.x+radius*cos(a),center.y+radius*sin(a))
            }.toMap()
            edges.filter{it.source in pos && it.target in pos}.forEach{e->
                drawLine(
                    color=androidx.compose.ui.graphics.Color.Gray,
                    start=pos[e.source]!!,end=pos[e.target]!!,
                    strokeWidth=(1f+2f*kotlin.math.abs(e.score)).coerceAtMost(5f)
                )
            }
            pos.values.forEach{p->
                drawCircle(androidx.compose.ui.graphics.Color.DarkGray,10f,p)
            }
        }
    }
}

@Composable
fun WorldMapCanvas(locations:List<WorldLocationItem>, modifier:Modifier=Modifier){
    Box(modifier){
        Canvas(Modifier.fillMaxSize()){
            val shown=locations.take(60)
            if(shown.isEmpty()) return@Canvas
            val cols=8
            val cellW=size.width/cols
            val rows=((shown.size+cols-1)/cols).coerceAtLeast(1)
            val cellH=size.height/rows
            shown.forEachIndexed{i,_->
                val x=(i%cols+0.5f)*cellW
                val y=(i/cols+0.5f)*cellH
                drawCircle(androidx.compose.ui.graphics.Color.DarkGray,8f,Offset(x,y))
            }
        }
    }
}
