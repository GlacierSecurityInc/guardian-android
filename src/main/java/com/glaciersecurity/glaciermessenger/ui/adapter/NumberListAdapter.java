package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.PurchaseNumberActivity;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class NumberListAdapter extends RecyclerView.Adapter<NumberListAdapter.viewHolder> {
    PurchaseNumberActivity purchaseNumbers;
    public NumberListAdapter(PurchaseNumberActivity purchaseNumbers) {
        this.purchaseNumbers = purchaseNumbers;
    }

    @NonNull
    @Override
    public viewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.add_number_list_item,parent,false);
        return new viewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull viewHolder holder, int position) {
        PurchaseNumberActivity.PhoneNumberDetails model = purchaseNumbers.availablePhoneNumbers.get(position);
        holder.number.setText(model.getPhone_number());
    }

    @Override
    public int getItemCount() {
        if(purchaseNumbers.availablePhoneNumbers != null)
            return purchaseNumbers.availablePhoneNumbers.size();
        return 0;
    }

    public class viewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        TextView number;
        LinearLayout item;
        public viewHolder(View view) {
            super(view);
            number = view.findViewById(R.id.number);
            item = view.findViewById(R.id.item);
            view.setOnClickListener(this);
            item.setOnClickListener(view1 -> {
                int position = getAdapterPosition();
                PurchaseNumberActivity.PhoneNumberDetails model = purchaseNumbers.availablePhoneNumbers.get(position);
                purchaseNumbers.OnNumberClick(model.getPhone_number());
            });
        }
        @Override
        public void onClick(View view) {
            int position = getAdapterPosition();
            PurchaseNumberActivity.PhoneNumberDetails model = purchaseNumbers.availablePhoneNumbers.get(position);
            purchaseNumbers.OnNumberClick(model.getPhone_number());
        }

    }
}
