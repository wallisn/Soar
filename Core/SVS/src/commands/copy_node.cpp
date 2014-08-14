#include <iostream>
#include <string>
#include "command.h"
#include "filter.h"
#include "svs.h"
#include "scene.h"

using namespace std;

class copy_node_command : public command
{
    public:
        copy_node_command(svs_state* state, Symbol* root)
            : command(state, root), root(root), first(true)
        {
            si = state->get_svs()->get_soar_interface();
            scn = state->get_scene();
        }
        
        string description()
        {
            return string("copy-node");
        }
        
        bool update_sub()
        {
            if (first)
            {
                first = false;
                if(!parse()){
                  return false;
                }
                return copy_node();
            }
            return true;
        }
        
        
        bool early()
        {
            return false;
        }
        
        bool parse(){
            // ^parent <id>
            // The name of the parent to attach the node to
            // Default is the root
            string parent_name;
            if(!si->get_const_attr(root, "parent", parent_name)){
              parent = scn->get_root();
            } else {
              sgnode* parent_node = scn->get_node(parent_name);
              if(parent_node == NULL){
                set_status("no parent node found");
                return false;
              }
              parent = dynamic_cast<group_node*>(parent_node);
              if(parent == NULL){
                set_status("parent must be a group node");
                return false;
              }
            }

            // source <id>
            // The id of the node to copy 
            string source_id;
            if (!si->get_const_attr(root, "source-id", source_id)){
              set_status("must specify a source-id");
              return false;
            }
            
            source_node = scn->get_node(source_id);
            if (!source_node) {
                set_status("Could not find the given source node");
                return false;
            }

            // id <id>
            // the id of the node to create
            if(!si->get_const_attr(root, "id", node_name)){
              set_status("^id must be specified");
              return false;
            }
            if(scn->get_node(node_name)){
              set_status("Node already exists");
              return false;
            }

            return true;
        }

        bool copy_node(){
            sgnode* dest_node;

            const ball_node* sourceBall = dynamic_cast<const ball_node*>(source_node);
            const convex_node* sourceConvex = dynamic_cast<const convex_node*>(source_node);
            if (sourceBall){
                double radius = sourceBall->get_radius();
                dest_node = new ball_node(node_name, "object", radius);
            } else if (sourceConvex) {
                ptlist points(sourceConvex->get_verts());
                dest_node = new convex_node(node_name, "object", points);
            } else {
                dest_node = new group_node(node_name, "object");
            }

            parent->attach_child(dest_node);
            
            vec3 pos, rot, scale;
            source_node->get_trans(pos, rot, scale);
            dest_node->set_trans(pos, rot, scale);
            
            set_status("success");
            return true;
        }
        
    private:
        Symbol*         root;
        scene*          scn;
        soar_interface* si;
        bool first;

        const sgnode* source_node;
        group_node* parent;
        string node_name;
};

command* _make_copy_node_command_(svs_state* state, Symbol* root)
{
    return new copy_node_command(state, root);
}
