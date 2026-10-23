package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;


import com.glaciersecurity.glaciermessenger.R;

import java.util.ArrayList;
import java.util.List;

public class SecurityHubListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<ExpandableListItem> items = new ArrayList<>();
    private Context context;
    private OnItemClickListener mOnItemClickListener;

    public void updateSechubList(ArrayList<ExpandableListItem> sechubList) {
        items = sechubList;
        notifyDataSetChanged();
    }

    private RecyclerView attachedRecyclerView;

    public void updateCoreConnect(boolean bool) {
        ExpandableListItem coreConnect;
        if (bool) {
            coreConnect =  new ExpandableListItem(R.drawable.key_vpn_enabled,  context.getString(R.string.core_connect), context.getString(R.string.core_connect_enabled), true, false);
        } else {
            coreConnect = new ExpandableListItem(R.drawable.key_vpn_disabled, context.getString(R.string.core_connect), context.getString(R.string.core_connect_disabled), true, false);
        }
        for (int i = 0; i <items.size(); i++){
            if (items.get(i).equals(coreConnect)){
                items.set(i, coreConnect);
                safeNotifyItemChanged(i);
                return;
            }
        }
    }

    public void updateDns(boolean usingDNSaddr) {
        ExpandableListItem dnsEnabled;
        if (usingDNSaddr) {
            dnsEnabled =  new ExpandableListItem(R.drawable.box_dns,  context.getString(R.string.nextdns_title), context.getString(R.string.dns_enabled), true, false);
        } else {
            dnsEnabled =  new ExpandableListItem(R.drawable.box_dns_disabled, context.getString(R.string.nextdns_title), context.getString(R.string.dns_disabled), true, false);
        }
        for (int i = 0; i <items.size(); i++){
            if (items.get(i).equals(dnsEnabled)){
                items.set(i, dnsEnabled);
                safeNotifyItemChanged(i);
                return;
            }
        }
    }

    private void safeNotifyItemChanged(final int position) {
        if (attachedRecyclerView != null && attachedRecyclerView.isComputingLayout()) {
            attachedRecyclerView.post(new Runnable() {
                @Override
                public void run() {
                    notifyItemChanged(position);
                }
            });
        } else {
            notifyItemChanged(position);
        }
    }


    public interface OnItemClickListener {
        void onItemClick(ExpandableListItem obj, int position);
    }

    public void setOnItemClickListener(final OnItemClickListener mItemClickListener) {
        this.mOnItemClickListener = mItemClickListener;
    }

    public SecurityHubListAdapter(Context context, List<ExpandableListItem> items) {
        this.context = context;
        this.items = items;
    }

    public static class SecurityHubEntryViewHolder extends RecyclerView.ViewHolder {
        public ImageView image;
        public TextView name;
        public TextView description;

        public ImageView bt_expand;
        public View lyt_parent;

        public SecurityHubEntryViewHolder(View v) {
            super(v);
            image = (ImageView) v.findViewById(R.id.image);
            name = (TextView) v.findViewById(R.id.name);
            description = (TextView) v.findViewById(R.id.description);
            bt_expand = (ImageView) v.findViewById(R.id.bt_expand);
            lyt_parent = (View) v.findViewById(R.id.lyt_parent);
        }
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        RecyclerView.ViewHolder vh;
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_expand, parent, false);
        vh = new SecurityHubEntryViewHolder(v);
        return vh;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") final int position) {
        if (holder instanceof SecurityHubEntryViewHolder) {
            final SecurityHubEntryViewHolder view = (SecurityHubEntryViewHolder) holder;

            final ExpandableListItem p = items.get(holder.getAdapterPosition());
            view.name.setText(p.name);
            final String description = p.isExpanded && p.expandedDescription != null
                    ? p.expandedDescription
                    : p.description;
            view.description.setText(description);
            view.description.setMaxLines(p.isExpanded ? Integer.MAX_VALUE : 2);
            view.description.setEllipsize(p.isExpanded ? null : TextUtils.TruncateAt.END);
            view.image.setImageResource(p.image);
            view.lyt_parent.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (mOnItemClickListener != null) {
                        mOnItemClickListener.onItemClick(items.get(holder.getAdapterPosition()), holder.getAdapterPosition());
                    }
                }
            });

            if (p.isExpandable) {
                view.bt_expand.setVisibility(View.VISIBLE);
                view.bt_expand.setRotation(p.isExpanded ? 90f : 0f);
            } else {
                view.bt_expand.setVisibility(View.INVISIBLE);
                view.bt_expand.setRotation(0f);
            }

        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        attachedRecyclerView = recyclerView;
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
            }
        });
        super.onAttachedToRecyclerView(recyclerView);
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        attachedRecyclerView = null;
    }

}
